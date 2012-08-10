/**
 * Copyright 2012 Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.facebook.swift.service;

import com.facebook.swift.codec.ThriftCodecManager;
import com.facebook.swift.service.metadata.ThriftMethodMetadata;
import com.facebook.swift.service.metadata.ThriftServiceMetadata;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import org.apache.thrift.TApplicationException;
import org.apache.thrift.TException;
import org.apache.thrift.TProcessor;
import org.apache.thrift.protocol.TMessage;
import org.apache.thrift.protocol.TMessageType;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolUtil;
import org.apache.thrift.protocol.TType;

import javax.annotation.concurrent.ThreadSafe;
import java.util.List;
import java.util.Map;

import static org.apache.thrift.TApplicationException.UNKNOWN_METHOD;

/**
 * Example TProcessor that wraps a Thrift service.  This should only be considered an example, and
 * is not production ready.  For example, this class makes assumptions about the thrift id of
 * method parameters, and does not support Thrift exceptions properly.
 */
@ThreadSafe
public class ThriftServiceProcessor implements TProcessor
{
    private final Map<String, ThriftMethodProcessor> methods;
    private final Multimap<String, ThriftMethodStats> serviceStats;

    /**
     * @param services the services to expose; services must be thread safe
     */
    public ThriftServiceProcessor(ThriftCodecManager codecManager, Object... services)
    {
        this(codecManager, ImmutableList.copyOf(services));
    }

    public ThriftServiceProcessor(ThriftCodecManager codecManager, List<Object> services)
    {
        Preconditions.checkNotNull(codecManager, "codecManager is null");
        Preconditions.checkNotNull(services, "service is null");
        Preconditions.checkArgument(!services.isEmpty(), "services is empty");

        // NOTE: ImmutableMap enforces that we don't have duplicate method names
        ImmutableMap.Builder<String, ThriftMethodProcessor> processorBuilder = ImmutableMap.builder();
        ImmutableMultimap.Builder<String, ThriftMethodStats> statsBuilder = ImmutableMultimap.builder();
        for (Object service : services) {
            ThriftServiceMetadata serviceMetadata = new ThriftServiceMetadata(service.getClass(), codecManager.getCatalog());
            for (ThriftMethodMetadata methodMetadata : serviceMetadata.getMethods().values()) {
                ThriftMethodProcessor methodProcessor = new ThriftMethodProcessor(service, methodMetadata, codecManager);
                processorBuilder.put(methodMetadata.getName(), methodProcessor);
                statsBuilder.put(serviceMetadata.getName(), methodProcessor.getStats());
            }
        }
        methods = processorBuilder.build();
        serviceStats = statsBuilder.build();
    }

    public Map<String, ThriftMethodProcessor> getMethods()
    {
        return methods;
    }

    public Multimap<String, ThriftMethodStats> getServiceStats()
    {
        return serviceStats;
    }

    @Override
    public boolean process(TProtocol in, TProtocol out)
            throws TException
    {
        TMessage message = in.readMessageBegin();
        String methodName = message.name;
        int sequenceId = message.seqid;

        try {
            // lookup method
            ThriftMethodProcessor method = methods.get(methodName);
            if (method == null) {
                TProtocolUtil.skip(in, TType.STRUCT);
                throw new TApplicationException(UNKNOWN_METHOD, "Invalid method name: '" + methodName + "'");
            }

            // invoke method
            method.process(in, out, sequenceId);

            return true;
        }
        catch (TApplicationException e) {
            // Application exceptions are sent to client, and the connection can be reused
            out.writeMessageBegin(new TMessage(methodName, TMessageType.EXCEPTION, sequenceId));
            e.write(out);
            out.writeMessageEnd();
            out.getTransport().flush();
            return true;
        }
        catch (Exception e) {
            // Other exceptions are not recoverable. The input or output streams may be corrupt.
            Throwables.propagateIfInstanceOf(e, TException.class);
            throw new TException(e);
        }
        finally {
            try {
                in.readMessageEnd();
            }
            catch (TException ignore) {
            }
        }
    }
}