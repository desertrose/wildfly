/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.jboss.as.clustering.jgroups.subsystem;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.jboss.as.clustering.controller.Operation;
import org.jboss.as.clustering.controller.ManagementRegistrar;
import org.jboss.as.clustering.controller.UnaryCapabilityNameResolver;
import org.jboss.as.controller.AbstractRuntimeOnlyHandler;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.as.controller.registry.ManagementResourceRegistration;
import org.jboss.dmr.ModelNode;
import org.jboss.msc.service.LifecycleEvent;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceName;
import org.wildfly.clustering.jgroups.spi.ChannelFactory;
import org.wildfly.clustering.jgroups.spi.JGroupsRequirement;
import org.wildfly.clustering.service.CountDownLifecycleListener;
import org.wildfly.clustering.service.FunctionalService;

/**
 * Operation handler for protocol stack diagnostic runtime operations.
 * @author Paul Ferraro
 */
public class StackOperationHandler extends AbstractRuntimeOnlyHandler implements ManagementRegistrar<ManagementResourceRegistration> {

    private final Map<String, Operation<ChannelFactory>> operations = new HashMap<>();

    public StackOperationHandler() {
        for (Operation<ChannelFactory> operation : EnumSet.allOf(StackOperation.class)) {
            this.operations.put(operation.getName(), operation);
        }
    }

    /* Method is synchronized to avoid duplicate service exceptions if called concurrently */
    @Override
    protected synchronized void executeRuntimeStep(OperationContext context, ModelNode op) throws OperationFailedException {
        String name = op.get(ModelDescriptionConstants.OP).asString();
        Operation<ChannelFactory> operation = this.operations.get(name);
        ServiceName serviceName = JGroupsRequirement.CHANNEL_FACTORY.getServiceName(context, UnaryCapabilityNameResolver.DEFAULT);
        Function<ChannelFactory, ModelNode> operationFunction = new Function<>() {
            @Override
            public ModelNode apply(ChannelFactory factory) {
                try {
                    return operation.execute(context, op, factory);
                } catch (OperationFailedException e) {
                    throw new RuntimeException(e);
                }
            }
        };
        ServiceBuilder<?> builder = context.getServiceTarget().addService(serviceName.append(this.getClass().getSimpleName()));
        Supplier<ChannelFactory> factory = builder.requires(serviceName);
        Reference<ModelNode> reference = new Reference<>();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch removeLatch = new CountDownLatch(1);
        ServiceController<?> controller = builder
                .addListener(new CountDownLifecycleListener(startLatch, EnumSet.of(LifecycleEvent.UP, LifecycleEvent.FAILED)))
                .addListener(new CountDownLifecycleListener(removeLatch, EnumSet.of(LifecycleEvent.REMOVED)))
                .setInstance(new FunctionalService<>(reference, operationFunction, factory))
                // Force ChannelFactory service to start
                .setInitialMode(ServiceController.Mode.ACTIVE)
                .install();
        try {
            startLatch.await();
            ModelNode result = reference.get();
            if (result != null) {
                context.getResult().set(result);
            } else {
                context.getFailureDescription().set(controller.getStartException().getLocalizedMessage());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OperationFailedException(e);
        } finally {
            // Make sure service is removed
            try {
                controller.setMode(ServiceController.Mode.REMOVE);
                removeLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            context.completeStep(OperationContext.ResultHandler.NOOP_RESULT_HANDLER);
        }
    }

    @Override
    public void register(ManagementResourceRegistration registration) {
        for (Operation<ChannelFactory> operation : EnumSet.allOf(StackOperation.class)) {
            registration.registerOperationHandler(operation.getDefinition(), this);
        }
    }

    static class Reference<T> implements Consumer<T>, Supplier<T> {
        private volatile T value;

        @Override
        public T get() {
            return this.value;
        }

        @Override
        public void accept(T value) {
            this.value = value;
        }
    }
}
