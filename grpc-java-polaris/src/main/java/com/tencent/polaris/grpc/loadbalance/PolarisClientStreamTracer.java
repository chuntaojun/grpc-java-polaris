package com.tencent.polaris.grpc.loadbalance;

import com.tencent.polaris.api.pojo.RetStatus;
import com.tencent.polaris.api.rpc.ServiceCallResult;
import com.tencent.polaris.grpc.util.ClientCallInfo;
import io.grpc.ClientStreamTracer;
import io.grpc.Metadata;
import io.grpc.Status;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * grpc 调用的 tracer 信息，记录每次 grpc 调用的情况
 * 1. 每次请求的相应时间
 * 2. 每次请求的结果，记录成功或者失败
 *
 * @author <a href="mailto:liaochuntao@live.com">liaochuntao</a>
 */
public class PolarisClientStreamTracer extends ClientStreamTracer {

    private final ClientCallInfo info;

    private final long startTime = System.currentTimeMillis();

    private final AtomicBoolean reported = new AtomicBoolean(false);

    private final ServiceCallResult result;

    public PolarisClientStreamTracer(StreamInfo info, Metadata headers, ClientCallInfo callInfo) {
        this.info = callInfo;
        this.result = new ServiceCallResult();

        this.result.setHost(callInfo.getInstance().getHost());
        this.result.setPort(callInfo.getInstance().getPort());
        this.result.setMethod(callInfo.getMethod());
        this.result.setNamespace(callInfo.getTargetNamespace());
        this.result.setService(callInfo.getTargetService());
    }

    /**
     * Stream is closed.  This will be called exactly once.
     */
    @Override
    public void streamClosed(Status status) {
        if (!reported.compareAndSet(false, true)) {
            return;
        }

        this.result.setRetStatus(status.isOk() ? RetStatus.RetSuccess : RetStatus.RetFail);
        this.result.setRetCode(status.getCode().value());
        this.result.setDelay(System.currentTimeMillis() - startTime);

        this.info.getConsumerAPI().updateServiceCallResult(result);
    }

    /**
     * An inbound message has been fully read from the transport.
     *
     * @param seqNo the sequential number of the message within the stream, starting from 0.  It can
     *              be used to correlate with {@link #inboundMessage(int)} for the same message.
     * @param optionalWireSize the wire size of the message. -1 if unknown
     * @param optionalUncompressedSize the uncompressed serialized size of the message. -1 if unknown
     */
    @Override
    public void inboundMessageRead(int seqNo, long optionalWireSize, long optionalUncompressedSize) {
        if (!reported.compareAndSet(false, true)) {
            return;
        }

        this.result.setRetStatus(RetStatus.RetSuccess);
        this.result.setRetCode(Status.OK.getCode().value());
        this.result.setDelay(System.currentTimeMillis() - startTime);

        this.info.getConsumerAPI().updateServiceCallResult(result);
    }


}
