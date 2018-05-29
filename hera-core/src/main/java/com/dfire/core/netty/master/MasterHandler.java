package com.dfire.core.netty.master;

import com.dfire.core.message.Protocol.*;
import com.dfire.core.netty.listener.ResponseListener;
import com.dfire.core.netty.master.response.*;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.extern.slf4j.Slf4j;

import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.*;

/**
 * @author: <a href="mailto:lingxiao@2dfire.com">凌霄</a>
 * @time: Created in 1:34 2018/1/4
 * @desc SocketMessage为rpc消息体
 */
@Slf4j
@ChannelHandler.Sharable
public class MasterHandler extends ChannelInboundHandlerAdapter {

    private CompletionService<ChannelResponse> completionService = new ExecutorCompletionService<ChannelResponse>(Executors.newCachedThreadPool());

    private MasterContext masterContext;

    private MasterHandleWebDebug handleWebDebug = new MasterHandleWebDebug();

    private MasterHandleHeartBeat masterDoHeartBeat = new MasterHandleHeartBeat();

    private MasterHandleWebCancel masterHandleCancelJob = new MasterHandleWebCancel();

    private MasterHandleWebExecute masterHandleWebExecute = new MasterHandleWebExecute();

    private MasterHandleWebUpdate masterHandleWebUpdate = new MasterHandleWebUpdate();


    public MasterHandler(MasterContext masterContext) {
        this.masterContext = masterContext;
        Executor executor = Executors.newSingleThreadExecutor();
        executor.execute(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        Future<ChannelResponse> future = completionService.take();
                        ChannelResponse response = future.get();
                        response.channel.write(wrapper(response.webResponse));
                        log.info("master get response thread success");
                    } catch (Exception e) {
                        log.error("master handler future take error");
                    }

                }
            }
        });
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        SocketMessage socketMessage = (SocketMessage) msg;
        Channel channel = ctx.channel();
        switch (socketMessage.getKind()) {
            //心跳
            case REQUEST:
                Request request = Request.newBuilder().mergeFrom(socketMessage.getBody()).build();
                if (request.getOperate() == Operate.HeartBeat) {
                    masterDoHeartBeat.handleHeartBeat(masterContext, channel, request);
                }
                break;
            case WEB_REUQEST:
                final WebRequest webRequest = WebRequest.newBuilder().mergeFrom(socketMessage.getBody()).build();
                switch (webRequest.getOperate()) {
                    case ExecuteDebug:
                        completionService.submit(new Callable<ChannelResponse>() {
                            @Override
                            public ChannelResponse call() throws Exception {
                                return new ChannelResponse(channel, handleWebDebug.handleWebDebug(masterContext, webRequest));
                            }
                        });
                        break;
                    case CancelJob:
                        completionService.submit(new Callable<ChannelResponse>() {
                            @Override
                            public ChannelResponse call() throws Exception {
                                return new ChannelResponse(channel, masterHandleCancelJob.handleWebCancel(masterContext, webRequest));
                            }
                        });
                        break;
                    case UpdateJob:
                        completionService.submit(new Callable<ChannelResponse>() {
                            @Override
                            public ChannelResponse call() throws Exception {
                                return new ChannelResponse(channel, masterHandleWebUpdate.handleWebUpdate(masterContext, webRequest));
                            }
                        });
                        break;
                    case ExecuteJob:
                        completionService.submit(new Callable<ChannelResponse>() {
                            @Override
                            public ChannelResponse call() throws Exception {
                                return new ChannelResponse(channel, masterHandleWebExecute.handleWebExecute(masterContext, webRequest));
                            }
                        });
                        break;
                }
                break;

            default:
                log.error("unknown request type : {}", socketMessage.getKind());
                break;
        }
        super.channelRead(ctx, msg);
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        Channel channel = ctx.channel();
        masterContext.getWorkMap().put(channel, new MasterWorkHolder(ctx.channel()));
        SocketAddress remoteAddress = channel.remoteAddress();
        log.info("worker client channel registered connect success : {}", remoteAddress.toString());
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        super.channelUnregistered(ctx);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        Channel channel = ctx.channel();
        SocketAddress remoteAddress = channel.remoteAddress();
        log.info("worker client channel active success : {}", remoteAddress.toString());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        super.exceptionCaught(ctx, cause);
    }

    private SocketMessage wrapper(WebResponse response) {
        return SocketMessage.newBuilder().setKind(SocketMessage.Kind.WEB_REUQEST).setBody(response.toByteString()).build();
    }

    private List<ResponseListener> listeners = new CopyOnWriteArrayList<ResponseListener>();

    public void addListener(ResponseListener listener) {
        listeners.add(listener);
    }

    public void removeListener(ResponseListener listener) {
        listeners.remove(listener);
    }



    private class ChannelResponse {
        Channel channel;
        WebResponse webResponse;

        public ChannelResponse(Channel channel, WebResponse webResponse) {
            this.channel = channel;
            this.webResponse = webResponse;
        }
    }
}
