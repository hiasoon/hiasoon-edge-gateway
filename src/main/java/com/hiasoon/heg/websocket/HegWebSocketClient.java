package com.hiasoon.heg.websocket;

import com.alibaba.fastjson.JSON;
import com.hiasoon.heg.config.ConfigPersistenceService;
import com.hiasoon.heg.config.HegConfig;
import com.hiasoon.heg.entity.HegOperationResponse;
import com.hiasoon.heg.executor.OperationDispatcher;
import com.hiasoon.heg.monitor.LogCollector;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URI;
import java.security.KeyStore;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class HegWebSocketClient {

    private static final String CONNECTION_NAME = "HEG云端WebSocket";

    private final HegConfig hegConfig;
    private final OperationDispatcher dispatcher;
    private final LogCollector logCollector;
    private final ConfigPersistenceService configPersistenceService;

    private volatile EventLoopGroup group;
    private volatile Channel channel;
    private ScheduledExecutorService reconnectScheduler;
    private ScheduledExecutorService heartbeatScheduler;
    private ScheduledFuture<?> reconnectFuture;
    private volatile int reconnectDelay = 1;
    private static final int MAX_RECONNECT_DELAY = 60;

    /**
     * 连接代次：每次发起一条新的连接（初始化/手动重连）都会递增。
     * 只有与当前代次一致的连接尝试才允许更新连接状态或继续调度重连，
     * 从而避免过期的失败/关闭回调覆盖新连接刚刚写入的「已连接」状态。
     */
    private final AtomicInteger connectionGeneration = new AtomicInteger(0);

    public HegWebSocketClient(HegConfig hegConfig, OperationDispatcher dispatcher, LogCollector logCollector, ConfigPersistenceService configPersistenceService) {
        this.hegConfig = hegConfig;
        this.dispatcher = dispatcher;
        this.logCollector = logCollector;
        this.configPersistenceService = configPersistenceService;
    }

    @PostConstruct
    public void init() {
        if (!isCloudConfigured()) {
            log.warn("未配置云端连接信息(websocket-url/app-id/secret-key)，跳过WebSocket连接与重试。"
                    + "如需启用请补全配置后重启，或通过监控接口更新配置后触发重连");
            logCollector.updateConnectionStatus(CONNECTION_NAME, false, "未配置云端连接信息，未建立连接");
            return;
        }
        log.info("正在初始化WebSocket连接（异步）...");
        validateTlsConfig();
        startConnectThread(connectionGeneration.incrementAndGet(), "heg-ws-client");
    }

    /**
     * 启动期校验 TLS 配置，避免配置错误拖到连接阶段才以「连接失败 + 无限重连」的形式暴露。
     *
     * <p>这里只做静态检查（文件是否存在、URL scheme 是否为明文），不发起 TLS 握手。
     */
    private void validateTlsConfig() {
        HegConfig.Tls tls = hegConfig.getCloud().getTls();
        if (tls == null) {
            return;
        }
        String wsUrl = hegConfig.getCloud().getWebsocketUrl();
        boolean wss = wsUrl != null && wsUrl.toLowerCase().startsWith("wss://");
        if (!wss && wsUrl != null) {
            log.warn("云端地址为明文 {}", wsUrl);
            log.warn("HEG 与云端之间的报文（含下发的 SQL、业务数据与握手 token）不会被加密，"
                    + "生产环境应使用 wss://");
        }
        if (tls.isInsecureSkipVerify()) {
            log.error("==================================================================");
            log.error("【严重安全风险】heg.cloud.tls.insecure-skip-verify=true");
            log.error("云端 TLS 证书将不做任何校验，链路可被中间人攻击解密与篡改。");
            log.error("该开关仅允许用于自签证书的临时联调环境，生产环境必须关闭。");
            log.error("==================================================================");
        }
        String trustStore = tls.getTrustStore();
        if (trustStore != null && !trustStore.trim().isEmpty()) {
            File store = new File(trustStore.trim());
            if (!store.isFile()) {
                throw new IllegalStateException(
                        "heg.cloud.tls.trust-store 指向的文件不存在: " + store.getAbsolutePath()
                                + "。请检查挂载路径，或清空该项以使用 JDK 默认信任库。");
            }
        }
    }

    @PreDestroy
    public void destroy() {
        // 递增代次，令所有在途连接尝试与重连任务失效
        connectionGeneration.incrementAndGet();
        cancelReconnect();
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdownNow();
        }
        if (reconnectScheduler != null) {
            reconnectScheduler.shutdownNow();
        }
        if (channel != null) {
            channel.close().syncUninterruptibly();
        }
        if (group != null) {
            group.shutdownGracefully();
        }
    }

    public synchronized void reconnect() {
        log.info("正在重新连接WebSocket...");
        // 递增代次：旧的连接线程、旧的延迟重连任务、旧连接的关闭回调都将因此失效
        final int generation = connectionGeneration.incrementAndGet();

        cancelReconnect();
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdownNow();
            heartbeatScheduler = null;
        }
        if (channel != null) {
            try {
                channel.close().syncUninterruptibly();
            } catch (Exception e) {
                log.warn("关闭旧连接异常", e);
            }
            channel = null;
        }
        if (group != null) {
            group.shutdownGracefully();
            group = null;
        }
        reconnectDelay = 1;

        startConnectThread(generation, "heg-ws-reconnect");
    }

    private void startConnectThread(final int generation, String threadName) {
        Thread wsThread = new Thread(() -> {
            try {
                connect(generation);
            } catch (Exception e) {
                log.error("WebSocket连接线程异常", e);
            }
        }, threadName);
        wsThread.setDaemon(true);
        wsThread.start();
    }

    private void connect(int generation) {
        // 未配置云端信息时不发起连接，也不进入重试循环
        if (!isCloudConfigured()) {
            log.warn("未配置云端连接信息(websocket-url/app-id/secret-key)，跳过WebSocket连接");
            if (generation == connectionGeneration.get()) {
                logCollector.updateConnectionStatus(CONNECTION_NAME, false, "未配置云端连接信息，未建立连接");
            }
            return;
        }
        // 若期间已发起更新的一次连接，本次尝试直接放弃
        if (generation != connectionGeneration.get()) {
            log.debug("连接代次已过期({})，放弃本次连接尝试", generation);
            return;
        }

        String wsUrl = hegConfig.getCloud().getWebsocketUrl();
        String appId = hegConfig.getCloud().getAppId();
        String secretKey = hegConfig.getCloud().getSecretKey();

        EventLoopGroup localGroup = null;
        try {
            String token = TokenGenerator.generate(appId, secretKey);
            String query = "appId=" + appId + "&token=" + token;
            String fullUri = wsUrl + (wsUrl.contains("?") ? "&" : "?") + query;
            URI uri = new URI(fullUri);

            String scheme = uri.getScheme() == null ? "ws" : uri.getScheme();
            final String host = uri.getHost() == null ? "localhost" : uri.getHost();
            final int port;
            if (uri.getPort() == -1) {
                if ("ws".equalsIgnoreCase(scheme)) {
                    port = 80;
                } else if ("wss".equalsIgnoreCase(scheme)) {
                    port = 443;
                } else {
                    port = 80;
                }
            } else {
                port = uri.getPort();
            }

            boolean ssl = "wss".equalsIgnoreCase(scheme);
            final SslContext sslCtx;
            if (ssl) {
                sslCtx = buildSslContext();
            } else {
                sslCtx = null;
            }
            // 仅在启用真实校验时设置主机名校验；跳过校验模式下设置该参数会使自签证书
            // 因 CN/SAN 不匹配而握手失败，与"跳过校验"的语义冲突
            final boolean verifyHostname = ssl && !isInsecureSkipVerify();

            final WebSocketClientHandshaker handshaker = WebSocketClientHandshakerFactory.newHandshaker(
                    uri, WebSocketVersion.V13, null, true, new DefaultHttpHeaders());

            localGroup = new NioEventLoopGroup();

            Bootstrap b = new Bootstrap();
            b.group(localGroup)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline p = ch.pipeline();
                            if (sslCtx != null) {
                                p.addLast(newSslHandler(sslCtx, ch, host, port, verifyHostname));
                            }
                            p.addLast(new HttpClientCodec());
                            p.addLast(new HttpObjectAggregator(8192));
                            p.addLast(new IdleStateHandler(30, 0, 0));
                            p.addLast(new HegWebSocketClientHandler(
                                    handshaker,
                                    dispatcher,
                                    HegWebSocketClient.this,
                                    generation
                            ));
                        }
                    });

            log.info("HEG连接云端: {}", fullUri);
            ChannelFuture future = b.connect(host, port).sync();

            // 连接建立过程中若已发起新连接，关闭这条多余连接并释放其线程组
            if (generation != connectionGeneration.get()) {
                log.info("连接建立时代次已过期({})，关闭本次连接", generation);
                future.channel().close().syncUninterruptibly();
                return;
            }

            Channel localChannel = future.channel();
            channel = localChannel;
            group = localGroup;

            handshaker.handshake(localChannel).sync();

            // 握手过程中若已发起新连接，同样放弃这条连接
            if (generation != connectionGeneration.get()) {
                log.info("握手完成时代次已过期({})，关闭本次连接", generation);
                localChannel.close().syncUninterruptibly();
                return;
            }

            logCollector.updateConnectionStatus(CONNECTION_NAME, true, "连接成功: " + fullUri);
            localChannel.closeFuture().sync();

            // 连接已关闭：仅清理本条连接自身的资源，避免误清后来者
            if (channel == localChannel) {
                channel = null;
            }
        } catch (Exception e) {
            if (generation == connectionGeneration.get()) {
                if (e instanceof IllegalStateException) {
                    // 配置类错误（如 trust-store 路径不存在）：重连也不会成功，
                    // 直接终止重试，避免 1→60s 的无限重连把根因淹没在日志里
                    log.error("WebSocket连接失败（配置错误，已停止重试）: {}", e.getMessage());
                    logCollector.updateConnectionStatus(CONNECTION_NAME, false, "配置错误: " + e.getMessage());
                    return;
                }
                log.error("WebSocket连接失败", e);
                logCollector.updateConnectionStatus(CONNECTION_NAME, false, "连接失败: " + e.getMessage());
                scheduleReconnect(generation);
            } else {
                log.info("过期连接尝试({})已结束，忽略其结果: {}", generation, e.getMessage());
            }
        } finally {
            // 无论成功与否都释放本次尝试创建的线程组（成功时连接关闭后随之释放）
            if (localGroup != null) {
                localGroup.shutdownGracefully();
                if (group == localGroup) {
                    group = null;
                }
            }
        }
    }

    /**
     * 构建云端连接的 TLS 上下文。
     *
     * <p>默认使用 JDK 内置信任库（cacerts），因此 Let's Encrypt 等公共 CA 签发的
     * 证书无需任何配置即可通过校验；私有 CA 场景通过 {@code heg.cloud.tls.trust-store}
     * 指定 PEM 证书或 JKS/PKCS12 密钥库。
     */
    private SslContext buildSslContext() throws Exception {
        HegConfig.Tls tls = hegConfig.getCloud().getTls();
        if (tls == null) {
            tls = new HegConfig.Tls();
        }
        SslContextBuilder builder = SslContextBuilder.forClient();
        if (tls.isInsecureSkipVerify()) {
            builder.trustManager(InsecureTrustManagerFactory.INSTANCE);
            return builder.build();
        }
        String trustStorePath = tls.getTrustStore();
        if (trustStorePath == null || trustStorePath.trim().isEmpty()) {
            log.info("TLS 使用 JDK 默认信任库（公共 CA 证书，如 Let's Encrypt）");
            return builder.build();
        }
        File store = new File(trustStorePath.trim());
        if (!store.isFile()) {
            // 配置错误必须阻断：静默退回默认信任库会让私有 CA 场景变成
            // "连不上又看不出原因"，而静默信任全部则等于没修
            throw new IllegalStateException(
                    "heg.cloud.tls.trust-store 指向的文件不存在: " + store.getAbsolutePath());
        }
        if (isKeyStoreFile(store.getName())) {
            builder.trustManager(loadTrustManagerFactory(store, tls.getTrustStorePassword()));
        } else {
            builder.trustManager(store);
        }
        log.info("TLS 信任库已加载: {}", store.getAbsolutePath());
        return builder.build();
    }

    private boolean isInsecureSkipVerify() {
        HegConfig.Tls tls = hegConfig.getCloud().getTls();
        return tls != null && tls.isInsecureSkipVerify();
    }

    /**
     * 创建 {@link SslHandler}，并在启用真实校验时打开主机名校验。
     *
     * <p>只做信任链校验是不够的：攻击者可以用任意一个合法 CA 为其自有域名申请证书，
     * 这类证书同样能通过信任链检查。必须显式设置
     * {@code endpointIdentificationAlgorithm=HTTPS}，才会校验证书 CN/SAN 与目标域名一致，
     * 否则「替换为另一个合法证书」的中间人攻击依然成立。
     */
    private static SslHandler newSslHandler(SslContext sslCtx, Channel ch, String host, int port,
                                            boolean verifyHostname) {
        SSLEngine engine = sslCtx.newEngine(ch.alloc(), host, port);
        if (verifyHostname) {
            SSLParameters params = engine.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            engine.setSSLParameters(params);
        }
        return new SslHandler(engine);
    }

    private static boolean isKeyStoreFile(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".jks") || lower.endsWith(".p12")
                || lower.endsWith(".pfx") || lower.endsWith(".pkcs12");
    }

    private static TrustManagerFactory loadTrustManagerFactory(File store, String password) throws Exception {
        KeyStore keyStore = store.getName().toLowerCase().endsWith(".jks")
                ? KeyStore.getInstance(KeyStore.getDefaultType())
                : KeyStore.getInstance("PKCS12");
        char[] pwd = (password == null || password.isEmpty()) ? null : password.toCharArray();
        try (InputStream in = new FileInputStream(store)) {
            keyStore.load(in, pwd);
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(keyStore);
        return tmf;
    }

    public void onChannelActive(int generation) {
        if (generation != connectionGeneration.get()) {
            log.debug("忽略过期连接({})的激活事件", generation);
            return;
        }
        log.info("WebSocket连接已激活");
        startHeartbeat(generation);
        resetReconnectDelay(generation);
    }

    public void onConnectionClosed(int generation) {
        if (generation != connectionGeneration.get()) {
            // 过期连接的关闭事件：可能是被主动重连关闭的旧连接，不应影响当前状态
            log.debug("忽略过期连接({})的关闭事件", generation);
            return;
        }
        log.warn("WebSocket连接已关闭，准备重连...");
        logCollector.updateConnectionStatus(CONNECTION_NAME, false, "连接已关闭，正在重连...");
        scheduleReconnect(generation);
    }

    private void scheduleReconnect(int generation) {
        if (generation != connectionGeneration.get()) {
            return;
        }
        if (reconnectScheduler == null) {
            reconnectScheduler = Executors.newSingleThreadScheduledExecutor();
        }

        log.info("{}秒后尝试重连...", reconnectDelay);
        cancelReconnect();
        reconnectFuture = reconnectScheduler.schedule(() -> {
            if (generation != connectionGeneration.get()) {
                return;
            }
            reconnectDelay = Math.min(reconnectDelay * 2, MAX_RECONNECT_DELAY);
            // 在独立线程中发起连接，避免阻塞重连调度线程
            startConnectThread(generation, "heg-ws-reconnect");
        }, reconnectDelay, TimeUnit.SECONDS);
    }

    private void cancelReconnect() {
        if (reconnectFuture != null) {
            reconnectFuture.cancel(false);
            reconnectFuture = null;
        }
    }

    public void sendResponse(HegOperationResponse response) {
        Channel current = channel;
        if (current != null && current.isActive()) {
            String json = JSON.toJSONString(response);
            WebSocketFrame frame = new TextWebSocketFrame(json);
            current.writeAndFlush(frame);
            log.debug("发送响应: requestId={}", response.getRequestId());
        } else {
            log.warn("WebSocket通道不可用，无法发送响应");
        }
    }

    public void startHeartbeat(int generation) {
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdownNow();
        }
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();
        heartbeatScheduler.scheduleAtFixedRate(() -> {
            if (generation != connectionGeneration.get()) {
                return;
            }
            Channel current = channel;
            if (current != null && current.isActive()) {
                current.writeAndFlush(new PingWebSocketFrame());
                log.debug("发送心跳ping");
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    public void resetReconnectDelay(int generation) {
        if (generation != connectionGeneration.get()) {
            return;
        }
        this.reconnectDelay = 1;
    }

    /**
     * 云端连接信息是否完整。任一必填项为空都视为未配置，此时不建立连接、不重试。
     */
    private boolean isCloudConfigured() {
        HegConfig.CloudConfig cloud = hegConfig.getCloud();
        if (cloud == null) {
            return false;
        }
        return !isBlank(cloud.getWebsocketUrl())
                && !isBlank(cloud.getAppId())
                && !isBlank(cloud.getSecretKey());
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
