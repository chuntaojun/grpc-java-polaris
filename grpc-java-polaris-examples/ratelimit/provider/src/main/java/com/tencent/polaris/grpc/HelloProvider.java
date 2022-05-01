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

package com.tencent.polaris.grpc;

import com.tencent.polaris.grpc.HelloPolaris.response;
import io.grpc.stub.StreamObserver;

/**
 * @author lixiaoshuang
 */
public class HelloProvider extends HelloGrpc.HelloImplBase {
    
    @Override
    public void sayHello(HelloPolaris.request request, StreamObserver<response> responseObserver) {
        String msg = request.getMsg();
        HelloPolaris.response response = HelloPolaris.response.newBuilder().setData(msg).build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
