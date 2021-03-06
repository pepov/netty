/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel.local;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MessageList;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.EventExecutorGroup;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import java.util.Deque;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;

public class LocalTransportThreadModelTest3 {

    enum EventType {
        EXCEPTION_CAUGHT,
        USER_EVENT,
        READ_SUSPEND,
        INACTIVE,
        ACTIVE,
        UNREGISTERED,
        REGISTERED,
        MESSAGE_RECEIVED,
        WRITE,
        READ
    }

    private static EventLoopGroup group;
    private static LocalAddress localAddr;

    @BeforeClass
    public static void init() {
        // Configure a test server
        group = new LocalEventLoopGroup();
        ServerBootstrap sb = new ServerBootstrap();
        sb.group(group)
                .channel(LocalServerChannel.class)
                .childHandler(new ChannelInitializer<LocalChannel>() {
                    @Override
                    public void initChannel(LocalChannel ch) throws Exception {
                        ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void messageReceived(ChannelHandlerContext ctx, MessageList<Object> msgs) {
                                // Discard
                                msgs.releaseAllAndRecycle();
                            }
                        });
                    }
                });

        localAddr = (LocalAddress) sb.bind(LocalAddress.ANY).syncUninterruptibly().channel().localAddress();
    }

    @AfterClass
    public static void destroy() throws Exception {
        group.shutdownGracefully().sync();
    }

    @Test(timeout = 60000)
    @Ignore("regression test")
    public void testConcurrentAddRemoveInboundEventsMultiple() throws Throwable {
        for (int i = 0; i < 50; i ++) {
            testConcurrentAddRemoveInboundEvents();
        }
    }

    @Test(timeout = 60000)
    @Ignore("regression test")
    public void testConcurrentAddRemoveOutboundEventsMultiple() throws Throwable {
        for (int i = 0; i < 50; i ++) {
            testConcurrentAddRemoveOutboundEvents();
        }
    }

    @Test(timeout = 30000)
    @Ignore("needs a fix")
    public void testConcurrentAddRemoveInboundEvents() throws Throwable {
        testConcurrentAddRemove(true);
    }

    @Test(timeout = 30000)
    @Ignore("needs a fix")
    public void testConcurrentAddRemoveOutboundEvents() throws Throwable {
        testConcurrentAddRemove(false);
    }

    private static void testConcurrentAddRemove(boolean inbound) throws Exception {
        EventLoopGroup l = new LocalEventLoopGroup(4, new DefaultThreadFactory("l"));
        EventExecutorGroup e1 = new DefaultEventExecutorGroup(4, new DefaultThreadFactory("e1"));
        EventExecutorGroup e2 = new DefaultEventExecutorGroup(4, new DefaultThreadFactory("e2"));
        EventExecutorGroup e3 = new DefaultEventExecutorGroup(4, new DefaultThreadFactory("e3"));
        EventExecutorGroup e4 = new DefaultEventExecutorGroup(4, new DefaultThreadFactory("e4"));
        EventExecutorGroup e5 = new DefaultEventExecutorGroup(4, new DefaultThreadFactory("e5"));

        final EventExecutorGroup[] groups = {e1, e2, e3, e4, e5};
        try {
            Deque<EventType> events = new ConcurrentLinkedDeque<EventType>();
            final EventForwarder h1 = new EventForwarder();
            final EventForwarder h2 = new EventForwarder();
            final EventForwarder h3 = new EventForwarder();
            final EventForwarder h4 = new EventForwarder();
            final EventForwarder h5 = new EventForwarder();
            final EventRecorder h6 = new EventRecorder(events, inbound);

            final Channel ch = new LocalChannel();
            if (!inbound) {
                ch.config().setAutoRead(false);
            }
            ch.pipeline().addLast(e1, h1)
                    .addLast(e1, h2)
                    .addLast(e1, h3)
                    .addLast(e1, h4)
                    .addLast(e1, h5)
                    .addLast(e1, "recorder", h6);

            l.register(ch).sync().channel().connect(localAddr).sync();

            final LinkedList<EventType> expectedEvents = events(inbound, 8192);

            Throwable cause = new Throwable();

            Thread pipelineModifier = new Thread(new Runnable() {
                @Override
                public void run() {
                    Random random = new Random();

                    while (true) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            return;
                        }
                        if (!ch.isRegistered()) {
                            continue;
                        }
                        //EventForwardHandler forwardHandler = forwarders[random.nextInt(forwarders.length)];
                        ChannelHandler handler = ch.pipeline().removeFirst();
                        ch.pipeline().addBefore(groups[random.nextInt(groups.length)], "recorder",
                                UUID.randomUUID().toString(), handler);
                    }
                }
            });
            pipelineModifier.setDaemon(true);
            pipelineModifier.start();
            for (EventType event: expectedEvents) {
                switch (event) {
                    case EXCEPTION_CAUGHT:
                        ch.pipeline().fireExceptionCaught(cause);
                        break;
                    case MESSAGE_RECEIVED:
                        ch.pipeline().fireMessageReceived("");
                        break;
                    case READ_SUSPEND:
                        ch.pipeline().fireChannelReadSuspended();
                        break;
                    case USER_EVENT:
                        ch.pipeline().fireUserEventTriggered("");
                        break;
                    case WRITE:
                        ch.pipeline().write("");
                        break;
                    case READ:
                        ch.pipeline().read();
                        break;
                }
            }

            ch.close().sync();

            while (events.peekLast() != EventType.UNREGISTERED) {
                Thread.sleep(10);
            }

            expectedEvents.addFirst(EventType.ACTIVE);
            expectedEvents.addFirst(EventType.REGISTERED);
            expectedEvents.addLast(EventType.INACTIVE);
            expectedEvents.addLast(EventType.UNREGISTERED);

            for (;;) {
                EventType event = events.poll();
                if (event == null) {
                    Assert.assertTrue("Missing events:" + expectedEvents.toString(), expectedEvents.isEmpty());
                    break;
                }
                Assert.assertEquals(event, expectedEvents.poll());
            }
        } finally {
            l.shutdownGracefully();
            e1.shutdownGracefully();
            e2.shutdownGracefully();
            e3.shutdownGracefully();
            e4.shutdownGracefully();
            e5.shutdownGracefully();

            l.terminationFuture().sync();
            e1.terminationFuture().sync();
            e2.terminationFuture().sync();
            e3.terminationFuture().sync();
            e4.terminationFuture().sync();
            e5.terminationFuture().sync();
        }
    }

    private static LinkedList<EventType> events(boolean inbound, int size) {
        EventType[] events;
        if (inbound) {
            events = new EventType[] {
                    EventType.USER_EVENT, EventType.MESSAGE_RECEIVED, EventType.READ_SUSPEND,
                    EventType.EXCEPTION_CAUGHT};
        } else {
            events = new EventType[] {
                    EventType.READ, EventType.WRITE, EventType.EXCEPTION_CAUGHT };
        }

        Random random = new Random();
        LinkedList<EventType> expectedEvents = new LinkedList<EventType>();
        for (int i = 0; i < size; i++) {
            expectedEvents.add(events[random.nextInt(events.length)]);
        }
        return expectedEvents;
    }

    @ChannelHandler.Sharable
    private static final class EventForwarder extends ChannelDuplexHandler { }

    private static final class EventRecorder extends ChannelDuplexHandler {
        private final Queue<EventType> events;
        private final boolean inbound;

        public EventRecorder(Queue<EventType> events, boolean inbound) {
            this.events = events;
            this.inbound = inbound;
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            events.add(EventType.EXCEPTION_CAUGHT);
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (inbound) {
                events.add(EventType.USER_EVENT);
            }
        }

        @Override
        public void channelReadSuspended(ChannelHandlerContext ctx) throws Exception {
            if (inbound) {
                events.add(EventType.READ_SUSPEND);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            events.add(EventType.INACTIVE);
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            events.add(EventType.ACTIVE);
        }

        @Override
        public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
            events.add(EventType.UNREGISTERED);
        }

        @Override
        public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
            events.add(EventType.REGISTERED);
        }

        @Override
        public void messageReceived(ChannelHandlerContext ctx, MessageList<Object> msgs) throws Exception {
            if (inbound) {
                events.add(EventType.MESSAGE_RECEIVED);
            }
        }

        @Override
        public void write(
                ChannelHandlerContext ctx, MessageList<Object> msgs, ChannelPromise promise) throws Exception {
            if (!inbound) {
                events.add(EventType.WRITE);
            }
        }

        @Override
        public void read(ChannelHandlerContext ctx) {
            if (!inbound) {
                events.add(EventType.READ);
            }
        }
    }
}
