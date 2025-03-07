/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.extension.messaging.activemq.jms;

import static org.apache.activemq.artemis.utils.SelectorTranslator.convertToActiveMQFilterString;
import static org.wildfly.extension.messaging.activemq.OperationDefinitionHelper.createNonEmptyStringAttribute;
import static org.wildfly.extension.messaging.activemq.jms.JMSQueueService.JMS_QUEUE_PREFIX;

import org.apache.activemq.artemis.api.core.management.QueueControl;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.jboss.as.controller.AttributeDefinition;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.dmr.ModelNode;
import org.wildfly.extension.messaging.activemq.AbstractQueueControlHandler;

/**
 * Handler for runtime operations that invoke on a ActiveMQ {@link QueueControl}.
 *
 * @author Brian Stansberry (c) 2011 Red Hat Inc.
 */
public class JMSQueueControlHandler extends AbstractQueueControlHandler<QueueControl> {

    public static final JMSQueueControlHandler INSTANCE = new JMSQueueControlHandler();

    private static final AttributeDefinition MESSAGE_ID = createNonEmptyStringAttribute("message-id");

    private JMSQueueControlHandler() {
    }

    @Override
    protected AttributeDefinition getMessageIDAttributeDefinition() {
        return MESSAGE_ID;
    }

    @Override
    protected AttributeDefinition[] getReplyMessageParameterDefinitions() {
        return JMSManagementHelper.JMS_MESSAGE_PARAMETERS;
    }

    @Override
    protected DelegatingQueueControl<QueueControl> getQueueControl(ActiveMQServer server, String queueName){
        String name = queueName;
        if (queueName.startsWith(JMS_QUEUE_PREFIX)) {
            name = queueName.substring(JMS_QUEUE_PREFIX.length());
        }
        QueueControl queueControl = QueueControl.class.cast(server.getManagementService().getResource(ResourceNames.QUEUE + JMS_QUEUE_PREFIX + name));
        if (queueControl == null) {
            //For backward compatibility
            queueControl = QueueControl.class.cast(server.getManagementService().getResource(ResourceNames.QUEUE + JMS_QUEUE_PREFIX + queueName));
            if (queueControl == null) {
                return null;
            }
        }
        final QueueControl control = queueControl;
        return new DelegatingQueueControl<QueueControl>() {

            @Override
            public QueueControl getDelegate() {
                return  control;
            }

            @Override
            public String listMessagesAsJSON(String filter) throws Exception {
                String result = control.listMessagesAsJSON(convertToActiveMQFilterString(filter));
                return convertToJMSProperties(result);
            }

            @Override
            public long countMessages(String filter) throws Exception {
                return control.countMessages(convertToActiveMQFilterString(filter));
            }

            @Override
            public boolean removeMessage(ModelNode id) throws Exception {
                int n = control.removeMessages(createFilterForJMSMessageID(id));
                return n == 1;
            }

            @Override
            public int removeMessages(String filter) throws Exception {
                return control.removeMessages(convertToActiveMQFilterString(filter));
            }

            @Override
            public int expireMessages(String filter) throws Exception {
                return control.expireMessages(convertToActiveMQFilterString(filter));
            }

            @Override
            public boolean expireMessage(ModelNode id) throws Exception {
                int n = control.expireMessages(createFilterForJMSMessageID(id));
                return n == 1;
            }

            @Override
            public boolean sendMessageToDeadLetterAddress(ModelNode id) throws Exception {
                int n = control.sendMessagesToDeadLetterAddress(createFilterForJMSMessageID(id));
                return n == 1;
            }

            @Override
            public int sendMessagesToDeadLetterAddress(String filter) throws Exception {
                return control.sendMessagesToDeadLetterAddress(convertToActiveMQFilterString(filter));
            }

            @Override
            public boolean changeMessagePriority(ModelNode id, int priority) throws Exception {
                int n = control.changeMessagesPriority(createFilterForJMSMessageID(id), priority);
                return n == 1;
            }

            @Override
            public int changeMessagesPriority(String filter, int priority) throws Exception {
                return control.changeMessagesPriority(convertToActiveMQFilterString(filter), priority);
            }

            @Override
            public boolean moveMessage(ModelNode id, String otherQueue) throws Exception {
                int n = control.moveMessages(createFilterForJMSMessageID(id), JMS_QUEUE_PREFIX + otherQueue);
                return n == 1;
            }

            @Override
            public boolean moveMessage(ModelNode id, String otherQueue, boolean rejectDuplicates) throws Exception {
                int n = control.moveMessages(createFilterForJMSMessageID(id), JMS_QUEUE_PREFIX + otherQueue, rejectDuplicates);
                return n == 1;
            }

            @Override
            public int moveMessages(String filter, String otherQueue) throws Exception {
                return control.moveMessages(convertToActiveMQFilterString(filter), JMS_QUEUE_PREFIX + otherQueue);
            }

            @Override
            public int moveMessages(String filter, String otherQueue, boolean rejectDuplicates) throws Exception {
                return control.moveMessages(convertToActiveMQFilterString(filter), JMS_QUEUE_PREFIX + otherQueue, rejectDuplicates);
            }

            @Override
            public String listMessageCounter() throws Exception {
                return control.listMessageCounter();
            }

            @Override
            public void resetMessageCounter() throws Exception {
                control.resetMessageCounter();
            }

            @Override
            public String listMessageCounterAsHTML() throws Exception {
                return control.listMessageCounterAsHTML();
            }

            @Override
            public String listMessageCounterHistory() throws Exception {
                return control.listMessageCounterHistory();
            }

            @Override
            public String listMessageCounterHistoryAsHTML() throws Exception {
                return control.listMessageCounterHistoryAsHTML();
            }

            @Override
            public void pause() throws Exception {
                control.pause();
            }

            @Override
            public void resume() throws Exception {
                control.resume();
            }

            @Override
            public String listConsumersAsJSON() throws Exception {
                return control.listConsumersAsJSON();
            }

            @Override
            public String listScheduledMessagesAsJSON() throws Exception {
                return control.listScheduledMessagesAsJSON();
            }

            @Override
            public String listDeliveringMessagesAsJSON() throws Exception {
                return control.listDeliveringMessagesAsJSON();
            }

            private String createFilterForJMSMessageID(ModelNode id) {
                return "AMQUserID='" + id.asString() + "'";
            }

            private String convertToJMSProperties(String text) {
                return text.replaceAll("priority", "JMSPriority")
                        .replaceAll("timestamp", "JMSTimestamp")
                        .replaceAll("expiration", "JMSExpiration")
                        .replaceAll("durable", "JMSDeliveryMode")
                        .replaceAll("userID", "JMSMessageID");
            }
        };
    }

    @Override
    protected Object handleAdditionalOperation(String operationName, ModelNode operation, OperationContext context,
                                               QueueControl queueControl) throws OperationFailedException {
        throwUnimplementedOperationException(operationName);
        return null;
    }

    @Override
    protected void revertAdditionalOperation(String operationName, ModelNode operation, OperationContext context, QueueControl queueControl, Object handback) {
        // no-op
    }
}
