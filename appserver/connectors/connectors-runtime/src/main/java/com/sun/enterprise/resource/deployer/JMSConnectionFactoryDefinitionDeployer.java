/*
 * Copyright (c) 2022 Contributors to the Eclipse Foundation
 * Copyright (c) 2012, 2020 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package com.sun.enterprise.resource.deployer;

import com.sun.appserv.connectors.internal.api.ConnectorConstants;
import com.sun.enterprise.config.serverbeans.Resource;
import com.sun.enterprise.config.serverbeans.Resources;
import com.sun.enterprise.deployment.JMSConnectionFactoryDefinitionDescriptor;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.beans.PropertyVetoException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Properties;

import org.glassfish.api.naming.SimpleJndiName;
import org.glassfish.connectors.config.ConnectorConnectionPool;
import org.glassfish.connectors.config.ConnectorResource;
import org.glassfish.connectors.config.SecurityMap;
import org.glassfish.resourcebase.resources.api.ResourceConflictException;
import org.glassfish.resourcebase.resources.api.ResourceDeployer;
import org.glassfish.resourcebase.resources.api.ResourceDeployerInfo;
import org.glassfish.resourcebase.resources.util.ResourceManagerFactory;
import org.jvnet.hk2.annotations.Service;
import org.jvnet.hk2.config.ConfigBeanProxy;
import org.jvnet.hk2.config.TransactionFailure;
import org.jvnet.hk2.config.types.Property;

import static com.sun.appserv.connectors.internal.api.ConnectorsUtil.deriveResourceName;
import org.glassfish.config.support.TranslatedConfigView;
import static org.glassfish.deployment.common.JavaEEResourceType.JMSCFDDPOOL;

@Service
@ResourceDeployerInfo(JMSConnectionFactoryDefinitionDescriptor.class)
public class JMSConnectionFactoryDefinitionDeployer implements ResourceDeployer<JMSConnectionFactoryDefinitionDescriptor> {

    private static final Logger LOG = System.getLogger(JMSConnectionFactoryDefinitionDeployer.class.getName());
    static final String PROPERTY_PREFIX = "org.glassfish.connector-connection-pool.";

    @Inject
    private Provider<ResourceManagerFactory> resourceManagerFactoryProvider;

    @Override
    public void deployResource(JMSConnectionFactoryDefinitionDescriptor resource, String applicationName, String moduleName) throws Exception {
        //TODO ASR
    }

    @Override
    public void deployResource(JMSConnectionFactoryDefinitionDescriptor resource) throws Exception {
        LOG.log(Level.DEBUG, "deployResource(resource.name={0})", resource.getName());
        SimpleJndiName poolName = deriveResourceName(resource.getResourceId(), resource.getJndiName(), JMSCFDDPOOL);
        SimpleJndiName resourceName = deriveResourceName(resource.getResourceId(), resource.getJndiName(), resource.getResourceType());
        ConnectorConnectionPool connectorCp = new MyJMSConnectionFactoryConnectionPool(resource, poolName);
        getDeployer(connectorCp).deployResource(connectorCp);
        ConnectorResource connectorResource = new MyJMSConnectionFactoryResource(poolName, resourceName);
        getDeployer(connectorResource).deployResource(connectorResource);
    }



    @Override
    public void validatePreservedResource(com.sun.enterprise.config.serverbeans.Application oldApp,
                                          com.sun.enterprise.config.serverbeans.Application newApp,
                                          Resource resource,
                                          Resources allResources)
    throws ResourceConflictException {
        //do nothing.
    }


    private ResourceDeployer getDeployer(Object resource) {
        return resourceManagerFactoryProvider.get().getResourceDeployer(resource);
    }

    private JMSConnectionFactoryProperty convertProperty(String name, String value) {
        return new JMSConnectionFactoryProperty(name, value);
    }

    @Override
    public void undeployResource(JMSConnectionFactoryDefinitionDescriptor resource, String applicationName, String moduleName) throws Exception {
        //TODO ASR
    }

    @Override
    public void undeployResource(JMSConnectionFactoryDefinitionDescriptor resource) throws Exception {
        LOG.log(Level.DEBUG, "undeployResource(resource.name={0})", resource.getName());
        SimpleJndiName poolName = deriveResourceName(resource.getResourceId(), resource.getJndiName(), JMSCFDDPOOL);
        SimpleJndiName resourceName = deriveResourceName(resource.getResourceId(), resource.getJndiName(), resource.getResourceType());
        ConnectorResource connectorResource = new MyJMSConnectionFactoryResource(poolName, resourceName);
        getDeployer(connectorResource).undeployResource(connectorResource);
        ConnectorConnectionPool connectorCp = new MyJMSConnectionFactoryConnectionPool(resource, poolName);
        getDeployer(connectorCp).undeployResource(connectorCp);
    }

    @Override
    public void enableResource(JMSConnectionFactoryDefinitionDescriptor resource) throws Exception {
        throw new UnsupportedOperationException("enable() not supported for jms-connection-factory-definition type");
    }

    @Override
    public void disableResource(JMSConnectionFactoryDefinitionDescriptor resource) throws Exception {
        throw new UnsupportedOperationException("disable() not supported for jms-connection-factory-definition type");
    }

    @Override
    public boolean handles(Object resource) {
        return resource instanceof JMSConnectionFactoryDefinitionDescriptor;
    }

    private boolean isValidProperty(String s) {
        return (s != null) && !s.equals("");
    }

    abstract class FakeConfigBean implements ConfigBeanProxy {

        @Override
        public ConfigBeanProxy deepCopy(ConfigBeanProxy parent) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ConfigBeanProxy getParent() {
            return null;
        }

        @Override
        public <T extends ConfigBeanProxy> T getParent(Class<T> tClass) {
            return null;
        }

        @Override
        public <T extends ConfigBeanProxy> T createChild(Class<T> tClass) throws TransactionFailure {
            return null;
        }
    }

    class JMSConnectionFactoryProperty extends FakeConfigBean implements Property {

        private String name;
        private String value;
        private String description;

        JMSConnectionFactoryProperty(String name, String value) {
            this.name = name;
            this.value = value;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public void setName(String value) throws PropertyVetoException {
            this.name = value;
        }

        @Override
        public String getValue() {
            return TranslatedConfigView.expandApplicationValue(value);
        }

        @Override
        public void setValue(String value) throws PropertyVetoException {
            this.value = value;
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public void setDescription(String value) throws PropertyVetoException {
            this.description = value;
        }

        public void injectedInto(Object o) {
            //do nothing
        }
    }

    class MyJMSConnectionFactoryResource extends FakeConfigBean implements ConnectorResource {

        private SimpleJndiName poolName;
        private SimpleJndiName jndiName;

        MyJMSConnectionFactoryResource(SimpleJndiName poolName, SimpleJndiName jndiName) {
            this.poolName = poolName;
            this.jndiName = jndiName;
        }

        @Override
        public String getPoolName() {
            return poolName.toString();
        }

        @Override
        public void setPoolName(String value) throws PropertyVetoException {
            this.poolName = new SimpleJndiName(value);
        }

        @Override
        public String getObjectType() {
            return null;
        }

        @Override
        public void setObjectType(String value) throws PropertyVetoException {
        }

        @Override
        public String getIdentity() {
            return jndiName.toString();
        }

        @Override
        public String getEnabled() {
            return String.valueOf(true);
        }

        @Override
        public void setEnabled(String value) throws PropertyVetoException {
        }

        @Override
        public String getDescription() {
            return null;
        }

        @Override
        public void setDescription(String value) throws PropertyVetoException {
        }

        @Override
        public List<Property> getProperty() {
            return null;
        }

        @Override
        public Property getProperty(String name) {
            return null;
        }

        @Override
        public String getPropertyValue(String name) {
            return null;
        }

        @Override
        public String getPropertyValue(String name, String defaultValue) {
            return null;
        }

        public void injectedInto(Object o) {
        }

        @Override
        public String getJndiName() {
            return jndiName.toString();
        }

        @Override
        public void setJndiName(String value) throws PropertyVetoException {
            this.jndiName = new SimpleJndiName(value);
        }

        @Override
        public String getDeploymentOrder() {
            return null;
        }

        @Override
        public void setDeploymentOrder(String value) {
            //do nothing
        }

        @Override
        public Property addProperty(Property prprt) {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public Property lookupProperty(String string) {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public Property removeProperty(String string) {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public Property removeProperty(Property prprt) {
            throw new UnsupportedOperationException("Not supported yet.");
        }
    }

    class MyJMSConnectionFactoryConnectionPool extends FakeConfigBean implements ConnectorConnectionPool {

        private final JMSConnectionFactoryDefinitionDescriptor desc;
        private final SimpleJndiName name;

        public MyJMSConnectionFactoryConnectionPool(JMSConnectionFactoryDefinitionDescriptor desc, SimpleJndiName name) {
            this.desc = desc;
            this.name = name;
        }

        @Override
        public String getObjectType() {
            return "user";  //To change body of implemented methods use File | Settings | File Templates.
        }

        @Override
        public void setObjectType(String value) throws PropertyVetoException {
            //To change body of implemented methods use File | Settings | File Templates.
        }

        @Override
        public String getIdentity() {
            return name.toString();
        }

        @Override
        public String getSteadyPoolSize() {
            int minPoolSize = desc.getMinPoolSize();
            if (minPoolSize >= 0) {
                return String.valueOf(minPoolSize);
            } else {
                return "8";
            }
        }

        @Override
        public void setSteadyPoolSize(String value) throws PropertyVetoException {
            //do nothing
        }

        @Override
        public String getMaxPoolSize() {
            int maxPoolSize = desc.getMaxPoolSize();
            if (maxPoolSize >= 0) {
                return String.valueOf(maxPoolSize);
            } else {
                return "32";
            }
        }

        @Override
        public void setMaxPoolSize(String value) throws PropertyVetoException {
            //do nothing
        }

        @Override
        public String getMaxWaitTimeInMillis() {
            String maxWaitTimeInMillis = desc.getProperty(PROPERTY_PREFIX + "max-wait-time-in-millis");
            if (isValidProperty(maxWaitTimeInMillis)) {
                return maxWaitTimeInMillis;
            } else {
                return "60000";
            }
        }

        @Override
        public void setMaxWaitTimeInMillis(String value) throws PropertyVetoException {
            //do nothing
        }

        @Override
        public String getPoolResizeQuantity() {
            String poolResizeQuantity = desc.getProperty(PROPERTY_PREFIX + "pool-resize-quantity");
            if (isValidProperty(poolResizeQuantity)) {
                return poolResizeQuantity;
            } else {
                return "2";
            }
        }

        @Override
        public void setPoolResizeQuantity(String value) throws PropertyVetoException {
            //do nothing
        }

        @Override
        public String getIdleTimeoutInSeconds() {
            String idleTimeoutInSeconds = desc.getProperty(PROPERTY_PREFIX + "idle-timeout-in-seconds");
            if (isValidProperty(idleTimeoutInSeconds)) {
                return idleTimeoutInSeconds;
            } else {
                return "300";
            }
        }

        @Override
        public void setIdleTimeoutInSeconds(String value) throws PropertyVetoException {
            //do nothing
        }

        @Override
        public String getIsConnectionValidationRequired() {
            String isConnectionValidationRequired = desc.getProperty(PROPERTY_PREFIX + "is-connection-validation-required");
            if (isValidProperty(isConnectionValidationRequired)) {
                return isConnectionValidationRequired;
            } else {
                return "false";
            }
        }

        @Override
        public void setIsConnectionValidationRequired(String value) throws PropertyVetoException {
            //do nothing
        }

        @Override
        public String getResourceAdapterName() {
            String resourceAdapter = desc.getResourceAdapter();
            if (isValidProperty(resourceAdapter)) {
                return resourceAdapter;
            } else {
                return ConnectorConstants.DEFAULT_JMS_ADAPTER;
            }
        }

        @Override
        public void setResourceAdapterName(String value) throws PropertyVetoException {
            //do nothing
        }

        @Override
        public String getConnectionDefinitionName() {
            String interfaceName = desc.getInterfaceName();
            if (isValidProperty(interfaceName)) {
                return interfaceName;
            } else {
                return "jakarta.jms.ConnectionFactory";
            }
        }

        @Override
        public void setConnectionDefinitionName(String value)  throws PropertyVetoException {
            //do nothing
        }

        @Override
        public String getFailAllConnections() {
            String failAllConnections = desc.getProperty(PROPERTY_PREFIX + "fail-all-connections");
            if (isValidProperty(failAllConnections)) {
                return failAllConnections;
            } else {
                return "false";
            }
        }

        @Override
        public void setFailAllConnections(String value) throws PropertyVetoException {
            //do nothing
        }

        @Override
        public String getTransactionSupport() {
            String transactionSupport = desc.getProperty(PROPERTY_PREFIX + "transaction-support");
            if (isValidProperty(transactionSupport)) {
                return transactionSupport;
            } else {
                return "NoTransaction";
            }
        }

        @Override
        public void setTransactionSupport(String value) throws PropertyVetoException {
            //do nothing
        }

        @Override
        public String getValidateAtmostOncePeriodInSeconds() {
            String validateAtmostOncePeriodInSeconds = desc.getProperty(PROPERTY_PREFIX + "validate-at-most-once-period-in-seconds");
            if (isValidProperty(validateAtmostOncePeriodInSeconds)) {
                return validateAtmostOncePeriodInSeconds;
            } else {
                return "0";
            }
        }

        @Override
        public void setValidateAtmostOncePeriodInSeconds(String value) throws PropertyVetoException {
            //do nothing
        }

        @Override
        public String getConnectionLeakTimeoutInSeconds() {
            String connectionLeakTimeoutInSeconds = desc.getProperty(PROPERTY_PREFIX + "connection-leak-timeout-in-seconds");
            if (isValidProperty(connectionLeakTimeoutInSeconds)) {
                return connectionLeakTimeoutInSeconds;
            } else {
                return "0";
            }
        }

        @Override
        public void setConnectionLeakTimeoutInSeconds(String value) throws PropertyVetoException {
            //do nothing
        }

        @Override
        public String getConnectionLeakReclaim() {
            String connectionLeakReclaim = desc.getProperty(PROPERTY_PREFIX + "connection-leak-reclaim");
            if (isValidProperty(connectionLeakReclaim)) {
                return connectionLeakReclaim;
            } else {
                return "0";
            }
        }

        @Override
        public void setConnectionLeakReclaim(String value) throws PropertyVetoException {
            //do nothing
        }

        @Override
        public String getConnectionCreationRetryAttempts() {
            String connectionCreationRetryAttempts = desc.getProperty(PROPERTY_PREFIX + "connection-creation-retry-attempts");
            if (isValidProperty(connectionCreationRetryAttempts)) {
                return connectionCreationRetryAttempts;
            } else {
                return "0";
            }
        }

        @Override
        public void setConnectionCreationRetryAttempts(String value) throws PropertyVetoException {
            //do nothing
        }

        @Override
        public String getConnectionCreationRetryIntervalInSeconds() {
            String connectionCreationRetryIntervalInSeconds = desc.getProperty(PROPERTY_PREFIX + "connection-creation-retry-interval-in-seconds");
            if (isValidProperty(connectionCreationRetryIntervalInSeconds)) {
                return connectionCreationRetryIntervalInSeconds;
            } else {
                return "0";
            }
        }

        @Override
        public void setConnectionCreationRetryIntervalInSeconds(String value) throws PropertyVetoException {
            //do nothing
        }

        @Override
        public String getLazyConnectionEnlistment() {
            String lazyConnectionEnlistment = desc.getProperty(PROPERTY_PREFIX + "lazy-connection-enlistment");
            if (isValidProperty(lazyConnectionEnlistment)) {
                return lazyConnectionEnlistment;
            } else {
                return "false";
            }
        }

        @Override
        public void setLazyConnectionEnlistment(String value) throws PropertyVetoException {
            //do nothing
        }

        @Override
        public String getLazyConnectionAssociation() {
            String lazyConnectionAssociation = desc.getProperty(PROPERTY_PREFIX + "lazy-connection-association");
            if (isValidProperty(lazyConnectionAssociation)) {
                return lazyConnectionAssociation;
            } else {
                return "false";
            }
        }

        @Override
        public void setLazyConnectionAssociation(String value) throws PropertyVetoException {
            //do nothing
        }

        @Override
        public String getAssociateWithThread() {
            String associateWithThread = desc.getProperty(PROPERTY_PREFIX + "associate-with-thread");
            if (isValidProperty(associateWithThread)) {
                return associateWithThread;
            } else {
                return "false";
            }
        }

        @Override
        public void setAssociateWithThread(String value) throws PropertyVetoException {
            //do nothing
        }

        @Override
        public String getPooling() {
            String pooling = desc.getProperty(PROPERTY_PREFIX + "pooling");
            if (isValidProperty(pooling)) {
                return pooling;
            } else {
                return "true";
            }
        }

        @Override
        public void setPooling(String value) throws PropertyVetoException {
            //do nothing
        }

        @Override
        public String getMatchConnections() {
            String matchConnections = desc.getProperty(PROPERTY_PREFIX + "match-connections");
            if (isValidProperty(matchConnections)) {
                return matchConnections;
            } else {
                return "true";
            }
        }

        @Override
        public void setMatchConnections(String value) throws PropertyVetoException {
            //do nothing
        }

        @Override
        public String getMaxConnectionUsageCount() {
            String maxConnectionUsageCount = desc.getProperty(PROPERTY_PREFIX + "max-connection-usage-count");
            if (isValidProperty(maxConnectionUsageCount)) {
                return maxConnectionUsageCount;
            } else {
                return "0";
            }
        }

        @Override
        public void setMaxConnectionUsageCount(String value) throws PropertyVetoException {
            //do nothing
        }

        @Override
        public String getDescription() {
            return desc.getDescription();
        }

        @Override
        public void setDescription(String value) throws PropertyVetoException {
            //do nothing
        }

        @Override
        public List<Property> getProperty() {
            Properties p = desc.getProperties();
            List<Property> jmsConnectionFactoryProperties = new ArrayList<>();

            if (isValidProperty(desc.getUser())) {
                JMSConnectionFactoryProperty property = convertProperty("UserName", desc.getUser());
                jmsConnectionFactoryProperties.add(property);
            }

            if (isValidProperty(desc.getPassword())) {
                JMSConnectionFactoryProperty property = convertProperty("Password", desc.getPassword());
                jmsConnectionFactoryProperties.add(property);
            }

            if (isValidProperty(desc.getClientId())) {
                JMSConnectionFactoryProperty property = convertProperty("clientId", desc.getClientId());
                jmsConnectionFactoryProperties.add(property);
            }

            for (Entry<Object, Object> entry : p.entrySet()) {
                String key = (String)entry.getKey();
                if (key.startsWith(PROPERTY_PREFIX)
                    || (key.equalsIgnoreCase("UserName") && isValidProperty(desc.getUser()))
                    || (key.equalsIgnoreCase("Password") && isValidProperty(desc.getPassword()))
                    || (key.equalsIgnoreCase("clientId") && isValidProperty(desc.getClientId()))) {
                    continue;
                }
                String value = (String)entry.getValue();
                JMSConnectionFactoryProperty property = convertProperty(key, value);
                jmsConnectionFactoryProperties.add(property);
            }

            return jmsConnectionFactoryProperties;
        }

        @Override
        public Property getProperty(String name) {
            String value = desc.getProperty(name);
            return new JMSConnectionFactoryProperty(name, value);
        }

        @Override
        public String getPropertyValue(String name) {
            return desc.getProperty(name);
        }

        @Override
        public String getPropertyValue(String name, String defaultValue) {
            String value = null;
            value = desc.getProperty(name);
            if (value != null) {
                return value;
            } else {
                return defaultValue;
            }
        }

        public void injectedInto(Object o) {
            //do nothing
        }

        @Override
        public String getName() {
            return name.toString();
        }

        @Override
        public void setName(String value) throws PropertyVetoException {
            //do nothing
        }

        @Override
        public String getPing() {
            String ping = desc.getProperty(PROPERTY_PREFIX + "ping");
            if (isValidProperty(ping)) {
                return ping;
            } else {
                return "false";
            }
        }

        @Override
        public void setPing(String value) throws PropertyVetoException {
            //do nothing
        }

        @Override
        public List<SecurityMap> getSecurityMap() {
            return new ArrayList<>(0);
        }

        @Override
        public String getDeploymentOrder() {
            return null;
        }

        @Override
        public void setDeploymentOrder(String value) {
            //do nothing
        }

        @Override
        public Property addProperty(Property prprt) {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public Property lookupProperty(String string) {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public Property removeProperty(String string) {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public Property removeProperty(Property prprt) {
            throw new UnsupportedOperationException("Not supported yet.");
        }
    }
}

