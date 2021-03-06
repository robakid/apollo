/*
 * Copyright (c) 2020, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.salesforce.apollo.comm;

import java.io.IOException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.salesforce.apollo.comm.ServerConnectionCache.ServerConnectionFactory;
import com.salesforce.apollo.membership.Member;
import com.salesforce.apollo.protocols.ClientIdentity;
import com.salesforce.apollo.protocols.HashKey;
import com.salesforce.apollo.protocols.Utils;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener;
import io.grpc.ForwardingServerCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.util.MutableHandlerRegistry;

/**
 * @author hal.hildebrand
 *
 */
public class LocalRouter extends Router {

    public static class LocalServerConnectionFactory implements ServerConnectionFactory {

        @Override
        public ManagedChannel connectTo(Member to, Member from) {
            ClientInterceptor clientInterceptor = new ClientInterceptor() {

                @Override
                public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
                                                                           CallOptions callOptions, Channel next) {
                    return new SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
                        @Override
                        public void start(Listener<RespT> responseListener, Metadata headers) {
                            headers.put(MEMBER_ID_KEY, from.getId().b64Encoded());
                            super.start(new SimpleForwardingClientCallListener<RespT>(responseListener) {
                            }, headers);
                        }
                    };
                }
            };
            return InProcessChannelBuilder.forName(to.getId().b64Encoded())
                                          .directExecutor()
                                          .intercept(clientInterceptor)
                                          .build();
        }
    }

    private static final class ThreadIdentity implements ClientIdentity {

        private final LoadingCache<X509Certificate, HashKey> cachedMembership = CacheBuilder.newBuilder()
                                                                                            .build(new CacheLoader<X509Certificate, HashKey>() {

                                                                                                @Override
                                                                                                public HashKey load(X509Certificate key) throws Exception {
                                                                                                    return Utils.getMemberId(key);
                                                                                                }
                                                                                            });

        @Override
        public X509Certificate getCert() {
            X509Certificate x509Certificate = callCertificate.get();
            if (x509Certificate == null) {
                throw new IllegalStateException("Thread local call certificate is NULL");
            }
            return x509Certificate;
        }

        @Override
        public Certificate[] getCerts() {
            return new Certificate[] { getCert() };
        }

        @Override
        public HashKey getFrom() {
            try {
                return cachedMembership.get(getCert());
            } catch (ExecutionException e) {
                throw new IllegalStateException("Unable to get member id from cert", e.getCause());
            }
        }

    }

    public static ThreadLocal<X509Certificate> callCertificate = new ThreadLocal<>();

    public static final ThreadIdentity       LOCAL_IDENTITY = new ThreadIdentity();
    public static final Metadata.Key<String> MEMBER_ID_KEY  = Metadata.Key.of("from.id",
                                                                              Metadata.ASCII_STRING_MARSHALLER);

    private static final Logger               log           = LoggerFactory.getLogger(LocalRouter.class);
    private static final Map<HashKey, Member> serverMembers = new ConcurrentHashMap<>();

    private final Member member;
    private final Server server;

    public LocalRouter(Member member, ServerConnectionCache.Builder builder, Executor executor) {
        this(member, builder, new MutableHandlerRegistry(), executor);
    }

    public LocalRouter(Member member, ServerConnectionCache.Builder builder, MutableHandlerRegistry registry,
            Executor executor) {
        super(builder.setFactory(new LocalServerConnectionFactory()).build(), registry);
        this.member = member;

        server = InProcessServerBuilder.forName(member.getId().b64Encoded())
                                       .executor(executor)
                                       .intercept(new ServerInterceptor() {

                                           @Override
                                           public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
                                                                                                        final Metadata requestHeaders,
                                                                                                        ServerCallHandler<ReqT, RespT> next) {
                                               String id = requestHeaders.get(MEMBER_ID_KEY);
                                               if (id == null) {
                                                   throw new IllegalStateException("No member ID in call");
                                               }
                                               Member member = serverMembers.get(new HashKey(id));
                                               if (member == null) {
                                                   throw new IllegalStateException("Invalid member ID in call: " + id);
                                               }
                                               callCertificate.set(member.getCertificate());
                                               return next.startCall(new ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(
                                                       call) {
                                               }, requestHeaders);
                                           }

                                       })
                                       .fallbackHandlerRegistry(registry)
                                       .build();
    }

    @Override
    public void close() {
        serverMembers.remove(member.getId());
        server.shutdownNow();
        super.close();
    }

    @Override
    public ClientIdentity getClientIdentityProvider() {
        return LOCAL_IDENTITY;
    }

    @Override
    public void start() {
        try {
            serverMembers.put(member.getId(), member);
            server.start();
        } catch (IOException e) {
            log.error("Cannot start in process server for: " + member, e);
        }
        log.info("Starting server for: " + member);
    }

}
