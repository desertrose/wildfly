/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.extension.messaging.activemq;

import java.util.Map;

import org.apache.activemq.artemis.api.core.management.ClusterConnectionControl;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.PathAddress;
import org.jboss.dmr.ModelNode;

/**
 * Handler for runtime operations that interact with a ActiveMQ {@link org.apache.activemq.api.core.management.ClusterConnectionControl}.
 *
 * @author Brian Stansberry (c) 2011 Red Hat Inc.
 */
public class ClusterConnectionControlHandler extends AbstractActiveMQComponentControlHandler<ClusterConnectionControl> {

    public static final ClusterConnectionControlHandler INSTANCE = new ClusterConnectionControlHandler();

    private ClusterConnectionControlHandler() {
    }

    @Override
    protected ClusterConnectionControl getActiveMQComponentControl(ActiveMQServer activeMQServer, PathAddress address) {
        final String resourceName = address.getLastElement().getValue();
        return ClusterConnectionControl.class.cast(activeMQServer.getManagementService().getResource(ResourceNames.CORE_CLUSTER_CONNECTION + resourceName));
    }

    @Override
    protected String getDescriptionPrefix() {
        return CommonAttributes.CLUSTER_CONNECTION;
    }

    @Override
    protected void handleReadAttribute(String attributeName, OperationContext context, ModelNode operation) throws OperationFailedException {
        if (ClusterConnectionDefinition.NODE_ID.getName().equals(attributeName)) {
            ClusterConnectionControl control = getActiveMQComponentControl(context, operation, false);
            if(control != null) {
                context.getResult().set(control.getNodeID());
            }
        } else if (ClusterConnectionDefinition.TOPOLOGY.getName().equals(attributeName)) {
            ClusterConnectionControl control = getActiveMQComponentControl(context, operation, false);
            if(control != null) {
                context.getResult().set(formatTopology(control.getTopology()));
            }
        } else {
            unsupportedAttribute(attributeName);
        }
    }

    public static String formatTopology(String topology) {
        String prefix = "";
        StringBuilder builder = new StringBuilder();
        boolean params = false;
        char previous = ' ';
        for (char c : topology.toCharArray()) {
            switch (c) {
                case '[':
                case '{':
                case '(':
                    builder.append(c);
                    prefix += TAB;
                    break;
                case '?':
                    if(' ' == previous) {
                        builder.deleteCharAt(builder.length()-1);
                        builder.append(',').append(NEW).append(prefix);
                    }
                    params = true;
                    prefix += TAB;
                    builder.append('{').append(NEW).append(prefix);
                    break;
                case '&':
                    builder.append(',').append(NEW).append(prefix);
                    break;
                case ',':
                    if(params) {
                        prefix = prefix.substring(0, prefix.length() - TAB.length());
                        builder.append(NEW).append(prefix).append('}').append(c).append(NEW).append(prefix);
                    } else {
                        builder.append(c);
                    }
                    params = false;
                    break;
                case ']':
                case '}':
                    prefix = prefix.substring(0, prefix.length() - TAB.length());
                    builder.append(NEW).append(prefix);
                    builder.append(c);
                    break;
                case ')':
                    prefix = prefix.substring(0, prefix.length() - TAB.length());
                    builder.append(c);
                    break;
                case ' ':
                    if (',' != previous) {
                        builder.append(c);
                    }
                    break;
                default:
                    if (',' == previous) {
                        builder.append(NEW).append(prefix);
                    }
                    builder.append(c);
            }
            if (c != ' ' || previous != ',') {
                previous = c;
            }
        }
        return builder.toString().trim();
    }

    private static final String TAB = "\t";
    private static final String NEW = System.lineSeparator();

    @Override
    protected Object handleOperation(String operationName, OperationContext context, ModelNode operation) throws OperationFailedException {
        if (ClusterConnectionDefinition.GET_NODES.equals(operationName)) {
            ClusterConnectionControl control = getActiveMQComponentControl(context, operation, false);
            try {
                if (control != null) {
                    Map<String, String> nodes = control.getNodes();
                    final ModelNode result = context.getResult();
                    result.setEmptyObject();
                    for (Map.Entry<String, String> entry : nodes.entrySet()) {
                        result.get(entry.getKey()).set(entry.getValue());
                    }
                }
            } catch (Exception e) {
                context.getFailureDescription().set(e.getLocalizedMessage());
            }
        } else {
            unsupportedOperation(operationName);
        }

        return null;
    }
}
