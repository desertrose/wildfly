/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.extension.clustering.ejb;

import java.util.function.UnaryOperator;

import org.jboss.as.clustering.controller.CapabilityProvider;
import org.jboss.as.clustering.controller.ChildResourceDefinition;
import org.jboss.as.clustering.controller.ResourceDescriptor;
import org.jboss.as.clustering.controller.ResourceServiceConfiguratorFactory;
import org.jboss.as.clustering.controller.ResourceServiceHandler;
import org.jboss.as.clustering.controller.SimpleResourceRegistrar;
import org.jboss.as.clustering.controller.SimpleResourceServiceHandler;
import org.jboss.as.clustering.controller.UnaryCapabilityNameResolver;
import org.jboss.as.clustering.controller.UnaryRequirementCapability;
import org.jboss.as.clustering.controller.validation.IntRangeValidatorBuilder;
import org.jboss.as.controller.AttributeDefinition;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.SimpleAttributeDefinitionBuilder;
import org.jboss.as.controller.capability.RuntimeCapability;
import org.jboss.as.controller.registry.ManagementResourceRegistration;
import org.jboss.as.controller.registry.AttributeAccess.Flag;
import org.jboss.dmr.ModelType;
import org.wildfly.clustering.ejb.bean.BeanProviderRequirement;
import org.wildfly.clustering.service.UnaryRequirement;

/**
 * Common resource definition for bean management resources.
 * @author Paul Ferraro
 */
public class BeanManagementResourceDefinition extends ChildResourceDefinition<ManagementResourceRegistration> {

    enum Capability implements CapabilityProvider, UnaryOperator<RuntimeCapability.Builder<Void>> {
        BEAN_MANAGEMENT_PROVIDER(BeanProviderRequirement.BEAN_MANAGEMENT_PROVIDER),
        ;
        private final org.jboss.as.clustering.controller.Capability capability;

        Capability(UnaryRequirement requirement) {
            this.capability = new UnaryRequirementCapability(requirement, this);
        }

        @Override
        public org.jboss.as.clustering.controller.Capability getCapability() {
            return this.capability;
        }

        @Override
        public RuntimeCapability.Builder<Void> apply(RuntimeCapability.Builder<Void> builder) {
            return builder.setDynamicNameMapper(UnaryCapabilityNameResolver.DEFAULT)
                    .addRequirements(ClientMappingsRegistryProviderResourceDefinition.Capability.CLIENT_MAPPINGS_REGISTRY_PROVIDER.getName());
        }
    }

    enum Attribute implements org.jboss.as.clustering.controller.Attribute, UnaryOperator<SimpleAttributeDefinitionBuilder> {
        MAX_ACTIVE_BEANS("max-active-beans", ModelType.INT) {
            @Override
            public SimpleAttributeDefinitionBuilder apply(SimpleAttributeDefinitionBuilder builder) {
                return builder.setAllowExpression(true).setValidator(new IntRangeValidatorBuilder().min(1).configure(builder).build());
            }
        },
        ;
        private final AttributeDefinition definition;

        Attribute(String name, ModelType type) {
            this.definition = this.apply(new SimpleAttributeDefinitionBuilder(name, type)
                    .setRequired(false)
                    .setFlags(Flag.RESTART_RESOURCE_SERVICES)
                ).build();
        }

        @Override
        public AttributeDefinition getDefinition() {
            return this.definition;
        }
    }

    private final UnaryOperator<ResourceDescriptor> configurator;
    private final ResourceServiceConfiguratorFactory factory;

    BeanManagementResourceDefinition(PathElement path, UnaryOperator<ResourceDescriptor> configurator, ResourceServiceConfiguratorFactory factory) {
        super(path, DistributableEjbExtension.SUBSYSTEM_RESOLVER.createChildResolver(path, PathElement.pathElement("bean-management")));
        this.configurator = configurator;
        this.factory = factory;
    }

    @Override
    public ManagementResourceRegistration register(ManagementResourceRegistration parent) {
        ManagementResourceRegistration registration = parent.registerSubModel(this);

        ResourceDescriptor descriptor = new ResourceDescriptor(this.getResourceDescriptionResolver())
                .addAttributes(Attribute.class)
                .addCapabilities(Capability.class)
                ;

        ResourceServiceHandler handler = new SimpleResourceServiceHandler(this.factory);

        new SimpleResourceRegistrar(this.configurator.apply(descriptor), handler).register(registration);

        return registration;
    }
}