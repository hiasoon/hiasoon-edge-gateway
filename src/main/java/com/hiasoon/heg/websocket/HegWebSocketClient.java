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
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.net.URI;
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
        startConnectThread(connectionGeneration.incrementAndGet(), "heg-ws-client");
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
                sslCtx = SslContextBuilder.forClient()
                        .trustManager(InsecureTrustManagerFactory.INSTANCE).build();
            } else {
                sslCtx = null;
            }

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
                                p.addLast(sslCtx.newHandler(ch.alloc(), host, port));
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
