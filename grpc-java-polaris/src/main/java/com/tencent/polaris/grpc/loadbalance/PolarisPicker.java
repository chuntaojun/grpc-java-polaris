/*
 * Tencent is pleased to support the open source community by making Polaris available.
 *
 * Copyright (C) 2019 THL A29 Limited, a Tencent company. All rights reserved.
 *
 * Licensed under the BSD 3-Clause License (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 * https://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.tencent.polaris.grpc.loadbalance;

import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.tencent.polaris.api.core.ConsumerAPI;
import com.tencent.polaris.api.exception.PolarisException;
import com.tencent.polaris.api.pojo.Instance;
import com.tencent.polaris.api.pojo.ServiceEventKey.EventType;
import com.tencent.polaris.api.pojo.ServiceInfo;
import com.tencent.polaris.api.pojo.ServiceKey;
import com.tencent.polaris.api.rpc.GetOneInstanceRequest;
import com.tencent.polaris.api.rpc.GetServiceRuleRequest;
import com.tencent.polaris.api.rpc.InstancesResponse;
import com.tencent.polaris.api.rpc.ServiceRuleResponse;
import com.tencent.polaris.client.pb.RoutingProto.Route;
import com.tencent.polaris.client.pb.RoutingProto.Routing;
import com.tencent.polaris.client.pb.RoutingProto.Source;
import com.tencent.polaris.grpc.util.ClientCallInfo;
import com.tencent.polaris.grpc.util.Common;
import com.tencent.polaris.grpc.util.PolarisHelper;
import io.grpc.Attributes;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.Metadata;
import io.grpc.Status;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.tencent.polaris.api.utils.RuleUtils.MATCH_ALL;

/**
 * The main balancing logic.  It <strong>must be thread-safe</strong>. Typically it should only
 * synchronize on its own state, and avoid synchronizing with the LoadBalancer's state.
 *
 * @author <a href="mailto:liaochuntao@live.com">liaochuntao</a>
 */
public class PolarisPicker extends SubchannelPicker {

    private static final Logger LOG = LoggerFactory.getLogger(PolarisPicker.class);

    private final Map<PolarisSubChannel, PolarisSubChannel> channels;

    private final ConsumerAPI consumerAPI;

    private final Attributes attributes;

    private final ServiceInfo sourceService;

    public PolarisPicker(final Map<PolarisSubChannel, PolarisSubChannel> channels,
            final ConsumerAPI consumerAPI, final ServiceInfo sourceService, final Attributes attributes) {
        this.channels = channels;
        this.consumerAPI = consumerAPI;
        this.attributes = attributes;
        this.sourceService = sourceService;
    }

    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
        if (channels.isEmpty()) {
            return PickResult.withNoResult();
        }

        final String targetNamespace = attributes.get(Common.TARGET_NAMESPACE_KEY);
        final String targetService = attributes.get(Common.TARGET_SERVICE_KEY);

        GetOneInstanceRequest request = createGetOneRequest(targetNamespace, targetService, args);

        try {
            InstancesResponse response = consumerAPI.getOneInstance(request);
            Instance instance = response.getInstances()[0];
            Subchannel channel = channels.get(new PolarisSubChannel(instance));

            return channel == null ? PickResult.withError(Status.NOT_FOUND) : PickResult.withSubchannel(channel,
                    new PolarisClientStreamTracerFactory(ClientCallInfo.builder()
                            .consumerAPI(consumerAPI)
                            .instance(instance)
                            .targetNamespace(targetNamespace)
                            .targetService(targetService)
                            .method(args.getMethodDescriptor().getBareMethodName())
                            .build()));
        } catch (PolarisException e) {
            LOG.error("[grpc-polaris] pick subChannel fail", e);
            return PickResult.withError(Status.UNKNOWN.withCause(e));
        }

    }

    private GetOneInstanceRequest createGetOneRequest(String targetNamespace, String targetService, PickSubchannelArgs args) {
        final ServiceKey target = new ServiceKey(targetNamespace, targetService);

        final GetOneInstanceRequest request = new GetOneInstanceRequest();
        request.setNamespace(targetNamespace);
        request.setService(targetService);

        final ServiceInfo serviceInfo = new ServiceInfo();
        ServiceKey source = null;

        if (Objects.nonNull(sourceService)) {
            request.setMetadata(sourceService.getMetadata());
            source = new ServiceKey(sourceService.getNamespace(), sourceService.getService());
            serviceInfo.setNamespace(sourceService.getNamespace());
            serviceInfo.setService(sourceService.getService());
        }
        serviceInfo.setMetadata(collectRoutingLabels(loadRouteRule(target, source), args.getHeaders()));
        request.setServiceInfo(serviceInfo);

        return request;
    }

    private Map<String, String> collectRoutingLabels(RouteResp routeResp, Metadata headers) {
        List<Route> routes = routeResp.doFilter();

        Set<String> labelKeys = new HashSet<>();

        routes.forEach(route -> {
            for (Source source : route.getSourcesList()) {
                labelKeys.addAll(source.getMetadataMap().keySet());
            }
        });

        Map<String, String> finalLabels = new HashMap<>();

        PolarisHelper.autoCollectLabels(headers, finalLabels, labelKeys);

        Map<String, String> customerLabels = PolarisHelper.getLabelsInject().injectRoutingLabels(headers);
        finalLabels.putAll(customerLabels);
        return finalLabels;
    }

    private RouteResp loadRouteRule(ServiceKey target, ServiceKey source) {

        GetServiceRuleRequest inBoundReq = new GetServiceRuleRequest();
        inBoundReq.setService(target.getService());
        inBoundReq.setNamespace(target.getNamespace());
        inBoundReq.setRuleType(EventType.ROUTING);

        ServiceRuleResponse  inBoundResp = consumerAPI.getServiceRule(inBoundReq);
        Routing inBoundRule  = (Routing) inBoundResp.getServiceRule().getRule();
        if (Objects.nonNull(inBoundRule)) {
            return new RouteResp(inBoundRule.getInboundsList(), target);
        }

        if (Objects.isNull(source)) {
            return new RouteResp(Collections.emptyList(), null);
        }

        GetServiceRuleRequest outBoundReq = new GetServiceRuleRequest();
        outBoundReq.setService(source.getService());
        outBoundReq.setNamespace(source.getNamespace());
        outBoundReq.setRuleType(EventType.ROUTING);

        ServiceRuleResponse outBoundResp = consumerAPI.getServiceRule(outBoundReq);
        Routing outBoundRule = (Routing) outBoundResp.getServiceRule().getRule();
        return new RouteResp(outBoundRule.getOutboundsList(), source);
    }

    public static final class EmptyPicker extends SubchannelPicker  {

        private final Status status;

        EmptyPicker(Status status) {
            this.status = Preconditions.checkNotNull(status, "status");
        }

        public PickResult pickSubchannel(PickSubchannelArgs args) {
            return this.status.isOk() ? PickResult.withNoResult() : PickResult.withError(this.status);
        }
    }

    private static class RouteResp {
        final List<Route> rule;
        final ServiceKey serviceKey;

        private RouteResp(List<Route> rule, ServiceKey serviceKey) {
            this.rule = rule;
            this.serviceKey = serviceKey;
        }


        List<Route> doFilter() {
            List<Route> newRule = rule.stream().filter((Predicate<Route>) route -> {
                for (Source source : route.getSourcesList()) {

                    if (Objects.equals(source.getNamespace().getValue(), MATCH_ALL) &&
                            Objects.equals(source.getService().getValue(), MATCH_ALL)) {
                        return true;
                    }

                    if (Objects.equals(source.getNamespace().getValue(), MATCH_ALL) &&
                            Objects.equals(source.getService().getValue(), serviceKey.getService())) {
                        return true;
                    }

                    if (Objects.equals(source.getNamespace().getValue(), serviceKey.getNamespace()) &&
                            Objects.equals(source.getService().getValue(), serviceKey.getService())) {
                        return true;
                    }
                }

                return false;
            }).collect(Collectors.toList());

            return newRule;
        }
    }
}
