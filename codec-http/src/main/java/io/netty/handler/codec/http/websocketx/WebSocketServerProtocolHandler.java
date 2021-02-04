/*
 * Copyright 2019 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.codec.http.websocketx;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.AttributeKey;

import java.util.List;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig.DEFAULT_HANDSHAKE_TIMEOUT_MILLIS;
import static io.netty.util.internal.ObjectUtil.checkNotNull;

/**
 * This handler does all the heavy lifting for you to run a websocket server.
 * <p>
 * 该处理程序为您运行Websocket服务器承担了所有繁重的工作。
 * <p>
 * It takes care of websocket handshaking as well as processing of control frames (Close, Ping, Pong). Text and Binary
 * data frames are passed to the next handler in the pipeline (implemented by you) for processing.
 * <p>
 * 它负责处理websocket握手以及处理控制帧（Close，Ping，Pong）。
 * 文本和二进制数据帧将传递到管道中的下一个处理程序（由您实现）以进行处理。
 * <p>
 * See <tt>io.netty.example.http.websocketx.html5.WebSocketServer</tt> for usage.
 * <p>
 * The implementation of this handler assumes that you just want to run  a websocket server and not process other types
 * HTTP requests (like GET and POST). If you wish to support both HTTP requests and websockets in the one server, refer
 * to the <tt>io.netty.example.http.websocketx.server.WebSocketServer</tt> example.
 * <p>
 * 此处理程序的实现假定您只想运行Websocket服务器，而不处理其他类型的HTTP请求（例如GET和POST）。
 * 如果希望在一台服务器上同时支持HTTP请求和Websocket，
 * 请参考<tt> io.netty.example.http.websocketx.server.WebSocketServer <tt>示例。
 * {@link io.netty.example.http.websocketx.server.WebSocketServer }
 * <p>
 * To know once a handshake was done you can intercept the
 * {@link ChannelInboundHandler#userEventTriggered(ChannelHandlerContext, Object)} and check if the event was instance
 * of {@link HandshakeComplete}, the event will contain extra information about the handshake such as the request and
 * selected subprotocol.
 * <p>
 * 要知道握手完成后，您可以拦截{@link ChannelInboundHandler#userEventTriggered(ChannelHandlerContext, Object)}
 * 并检查事件是否为{@link HandshakeComplete}的实例，该事件将包含有关握手的其他信息，例如请求和已选择子协议。
 */
// Web套接字服务器协议处理程序
//核心功能是将Http协议升级为ws（websocket）协议,保持长连接
public class WebSocketServerProtocolHandler extends WebSocketProtocolHandler {

    /**
     * Events that are fired to notify about handshake status
     * <p>
     * 触发以通知有关握手状态的事件
     */
    public enum ServerHandshakeStateEvent {
        /**
         * The Handshake was completed successfully and the channel was upgraded to websockets.
         * <p>
         * 握手已成功完成，并且该频道已升级为websockets。
         *
         * @deprecated in favor of {@link HandshakeComplete} class,
         * it provides extra information about the handshake
         * <p>
         * 支持{@link HandshakeComplete}类，它提供了有关握手的更多信息
         */
        @Deprecated
        HANDSHAKE_COMPLETE,

        /**
         * The Handshake was timed out
         */
        HANDSHAKE_TIMEOUT
    }

    /**
     * The Handshake was completed successfully and the channel was upgraded to websockets.
     * <p>
     * 握手已成功完成，并且该频道已升级为websockets。
     */
    public static final class HandshakeComplete {
        private final String requestUri;
        private final HttpHeaders requestHeaders;
        private final String selectedSubprotocol;

        HandshakeComplete(String requestUri, HttpHeaders requestHeaders, String selectedSubprotocol) {
            this.requestUri = requestUri;
            this.requestHeaders = requestHeaders;
            this.selectedSubprotocol = selectedSubprotocol;
        }

        public String requestUri() {
            return requestUri;
        }

        public HttpHeaders requestHeaders() {
            return requestHeaders;
        }

        public String selectedSubprotocol() {
            return selectedSubprotocol;
        }
    }

    private static final AttributeKey<WebSocketServerHandshaker> HANDSHAKER_ATTR_KEY =
            AttributeKey.valueOf(WebSocketServerHandshaker.class, "HANDSHAKER");

    private final WebSocketServerProtocolConfig serverConfig;

    /**
     * Base constructor
     *
     * @param serverConfig Server protocol configuration.
     */
    public WebSocketServerProtocolHandler(WebSocketServerProtocolConfig serverConfig) {
        super(checkNotNull(serverConfig, "serverConfig").dropPongFrames(),
                serverConfig.sendCloseFrame(),
                serverConfig.forceCloseTimeoutMillis()
        );
        this.serverConfig = serverConfig;
    }

    public WebSocketServerProtocolHandler(String websocketPath) {
        this(websocketPath, DEFAULT_HANDSHAKE_TIMEOUT_MILLIS);
    }

    public WebSocketServerProtocolHandler(String websocketPath, long handshakeTimeoutMillis) {
        this(websocketPath, false, handshakeTimeoutMillis);
    }

    public WebSocketServerProtocolHandler(String websocketPath, boolean checkStartsWith) {
        this(websocketPath, checkStartsWith, DEFAULT_HANDSHAKE_TIMEOUT_MILLIS);
    }

    public WebSocketServerProtocolHandler(String websocketPath, boolean checkStartsWith, long handshakeTimeoutMillis) {
        this(websocketPath, null, false, 65536, false, checkStartsWith, handshakeTimeoutMillis);
    }

    public WebSocketServerProtocolHandler(String websocketPath, String subprotocols) {
        this(websocketPath, subprotocols, DEFAULT_HANDSHAKE_TIMEOUT_MILLIS);
    }

    public WebSocketServerProtocolHandler(String websocketPath, String subprotocols, long handshakeTimeoutMillis) {
        this(websocketPath, subprotocols, false, handshakeTimeoutMillis);
    }

    public WebSocketServerProtocolHandler(String websocketPath, String subprotocols, boolean allowExtensions) {
        this(websocketPath, subprotocols, allowExtensions, DEFAULT_HANDSHAKE_TIMEOUT_MILLIS);
    }

    public WebSocketServerProtocolHandler(String websocketPath, String subprotocols, boolean allowExtensions,
                                          long handshakeTimeoutMillis) {
        this(websocketPath, subprotocols, allowExtensions, 65536, handshakeTimeoutMillis);
    }

    public WebSocketServerProtocolHandler(String websocketPath, String subprotocols,
                                          boolean allowExtensions, int maxFrameSize) {
        this(websocketPath, subprotocols, allowExtensions, maxFrameSize, DEFAULT_HANDSHAKE_TIMEOUT_MILLIS);
    }

    public WebSocketServerProtocolHandler(String websocketPath, String subprotocols,
                                          boolean allowExtensions, int maxFrameSize, long handshakeTimeoutMillis) {
        this(websocketPath, subprotocols, allowExtensions, maxFrameSize, false, handshakeTimeoutMillis);
    }

    public WebSocketServerProtocolHandler(String websocketPath, String subprotocols,
                                          boolean allowExtensions, int maxFrameSize, boolean allowMaskMismatch) {
        this(websocketPath, subprotocols, allowExtensions, maxFrameSize, allowMaskMismatch,
                DEFAULT_HANDSHAKE_TIMEOUT_MILLIS);
    }

    public WebSocketServerProtocolHandler(String websocketPath, String subprotocols, boolean allowExtensions,
                                          int maxFrameSize, boolean allowMaskMismatch, long handshakeTimeoutMillis) {
        this(websocketPath, subprotocols, allowExtensions, maxFrameSize, allowMaskMismatch, false,
                handshakeTimeoutMillis);
    }

    public WebSocketServerProtocolHandler(String websocketPath, String subprotocols,
                                          boolean allowExtensions, int maxFrameSize, boolean allowMaskMismatch, boolean checkStartsWith) {
        this(websocketPath, subprotocols, allowExtensions, maxFrameSize, allowMaskMismatch, checkStartsWith,
                DEFAULT_HANDSHAKE_TIMEOUT_MILLIS);
    }

    public WebSocketServerProtocolHandler(String websocketPath, String subprotocols,
                                          boolean allowExtensions, int maxFrameSize, boolean allowMaskMismatch,
                                          boolean checkStartsWith, long handshakeTimeoutMillis) {
        this(websocketPath, subprotocols, allowExtensions, maxFrameSize, allowMaskMismatch, checkStartsWith, true,
                handshakeTimeoutMillis);
    }

    public WebSocketServerProtocolHandler(String websocketPath, String subprotocols,
                                          boolean allowExtensions, int maxFrameSize, boolean allowMaskMismatch,
                                          boolean checkStartsWith, boolean dropPongFrames) {
        this(websocketPath, subprotocols, allowExtensions, maxFrameSize, allowMaskMismatch, checkStartsWith,
                dropPongFrames, DEFAULT_HANDSHAKE_TIMEOUT_MILLIS);
    }

    public WebSocketServerProtocolHandler(String websocketPath, String subprotocols, boolean allowExtensions,
                                          int maxFrameSize, boolean allowMaskMismatch, boolean checkStartsWith,
                                          boolean dropPongFrames, long handshakeTimeoutMillis) {
        this(websocketPath, subprotocols, checkStartsWith, dropPongFrames, handshakeTimeoutMillis,
                WebSocketDecoderConfig.newBuilder()
                        .maxFramePayloadLength(maxFrameSize)
                        .allowMaskMismatch(allowMaskMismatch)
                        .allowExtensions(allowExtensions)
                        .build());
    }

    public WebSocketServerProtocolHandler(String websocketPath, String subprotocols, boolean checkStartsWith,
                                          boolean dropPongFrames, long handshakeTimeoutMillis,
                                          WebSocketDecoderConfig decoderConfig) {
        this(WebSocketServerProtocolConfig.newBuilder()
                .websocketPath(websocketPath)
                .subprotocols(subprotocols)
                .checkStartsWith(checkStartsWith)
                .handshakeTimeoutMillis(handshakeTimeoutMillis)
                .dropPongFrames(dropPongFrames)
                .decoderConfig(decoderConfig)
                .build());
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        ChannelPipeline cp = ctx.pipeline();
        if (cp.get(WebSocketServerProtocolHandshakeHandler.class) == null) {
            // Add the WebSocketHandshakeHandler before this one.
            cp.addBefore(ctx.name(), WebSocketServerProtocolHandshakeHandler.class.getName(),
                    new WebSocketServerProtocolHandshakeHandler(serverConfig));
        }
        if (serverConfig.decoderConfig().withUTF8Validator() && cp.get(Utf8FrameValidator.class) == null) {
            // Add the UFT8 checking before this one.
            cp.addBefore(ctx.name(), Utf8FrameValidator.class.getName(),
                    new Utf8FrameValidator());
        }
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, WebSocketFrame frame, List<Object> out) throws Exception {
        if (serverConfig.handleCloseFrames() && frame instanceof CloseWebSocketFrame) {
            WebSocketServerHandshaker handshaker = getHandshaker(ctx.channel());
            if (handshaker != null) {
                frame.retain();
                handshaker.close(ctx.channel(), (CloseWebSocketFrame) frame);
            } else {
                ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
            }
            return;
        }
        super.decode(ctx, frame, out);
    }

    @Override
    protected WebSocketServerHandshakeException buildHandshakeException(String message) {
        return new WebSocketServerHandshakeException(message);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (cause instanceof WebSocketHandshakeException) {
            FullHttpResponse response = new DefaultFullHttpResponse(
                    HTTP_1_1, HttpResponseStatus.BAD_REQUEST, Unpooled.wrappedBuffer(cause.getMessage().getBytes()));
            ctx.channel().writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        } else {
            ctx.fireExceptionCaught(cause);
            ctx.close();
        }
    }

    static WebSocketServerHandshaker getHandshaker(Channel channel) {
        return channel.attr(HANDSHAKER_ATTR_KEY).get();
    }

    static void setHandshaker(Channel channel, WebSocketServerHandshaker handshaker) {
        channel.attr(HANDSHAKER_ATTR_KEY).set(handshaker);
    }
}
