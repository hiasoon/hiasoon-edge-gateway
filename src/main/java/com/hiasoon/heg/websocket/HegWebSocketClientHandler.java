package com.hiasoon.heg.websocket;

import com.alibaba.fastjson.JSON;
import com.hiasoon.heg.entity.HegOperationRequest;
import com.hiasoon.heg.entity.HegOperationResponse;
import com.hiasoon.heg.executor.OperationDispatcher;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.websocketx.*;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;

@Slf4j
public class HegWebSocketClientHandler extends SimpleChannelInboundHandler<Object> {

    private final WebSocketClientHandshaker handshaker;
    private final OperationDispatcher dispatcher;
    private final HegWebSocketClient client;
    /** 本连接所属的代次，用于让客户端忽略过期连接的 active/inactive 事件 */
    private final int generation;

    public HegWebSocketClientHandler(WebSocketClientHandshaker handshaker,
                                     OperationDispatcher dispatcher,
                                     HegWebSocketClient client,
                                     int generation) {
        this.handshaker = handshaker;
        this.dispatcher = dispatcher;
        this.client = client;
        this.generation = generation;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        client.onChannelActive(generation);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.warn("WebSocket连接已断开");
        client.onConnectionClosed(generation);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
        if (!handshaker.isHandshakeComplete()) {
            try {
                handshaker.finishHandshake(ctx.channel(), (FullHttpResponse) msg);
                log.info("WebSocket握手完成");
            } catch (WebSocketHandshakeException e) {
                log.error("WebSocket握手失败", e);
                ctx.channel().close();
            }
            return;
        }

        if (msg instanceof FullHttpResponse) {
            FullHttpResponse response = (FullHttpResponse) msg;
            throw new IllegalStateException("Unexpected FullHttpResponse: " + response.status());
        }

        WebSocketFrame frame = (WebSocketFrame) msg;

        if (frame instanceof TextWebSocketFrame) {
            TextWebSocketFrame textFrame = (TextWebSocketFrame) frame;
            String message = textFrame.text();
            handleMessage(message);
        } else if (frame instanceof PongWebSocketFrame) {
            log.debug("收到心跳pong");
        } else if (frame instanceof CloseWebSocketFrame) {
            log.warn("收到关闭帧");
            ctx.channel().close();
        }
    }

    private void handleMessage(String message) {
        if ("ping".equalsIgnoreCase(message.trim())) {
            log.debug("收到服务端ping");
            return;
        }

        try {
            HegOperationRequest request = JSON.parseObject(message, HegOperationRequest.class);
            log.info("收到请求: requestId={}, dbType={}, operationType={}",
                    request.getRequestId(), request.getDbType(), request.getOperationType());

            HegOperationResponse response = dispatcher.dispatch(request);
            client.sendResponse(response);

        } catch (Exception e) {
            log.error("处理消息失败: {}", message, e);
            try {
                HegOperationRequest request = JSON.parseObject(message, HegOperationRequest.class);
                HegOperationResponse response = HegOperationResponse.error(
                        request != null ? request.getRequestId() : null,
                        "EXECUTION_ERROR", e.getMessage());
                client.sendResponse(response);
            } catch (Exception parseEx) {
                log.error("解析请求失败", parseEx);
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("WebSocket异常", cause);
        ctx.close();
    }
}