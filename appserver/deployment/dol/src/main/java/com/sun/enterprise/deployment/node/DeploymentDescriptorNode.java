/*
 * Copyright (c) 2022, 2023 Contributors to the Eclipse Foundation.
 * Copyright (c) 1997, 2018 Oracle and/or its affiliates. All rights reserved.
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

package com.sun.enterprise.deployment.node;

import com.sun.enterprise.deployment.AdministeredObjectDefinitionDescriptor;
import com.sun.enterprise.deployment.ConnectionFactoryDefinitionDescriptor;
import com.sun.enterprise.deployment.DataSourceDefinitionDescriptor;
import com.sun.enterprise.deployment.EjbReferenceDescriptor;
import com.sun.enterprise.deployment.EntityManagerFactoryReferenceDescriptor;
import com.sun.enterprise.deployment.EntityManagerReferenceDescriptor;
import com.sun.enterprise.deployment.EnvironmentProperty;
import com.sun.enterprise.deployment.JMSConnectionFactoryDefinitionDescriptor;
import com.sun.enterprise.deployment.JMSDestinationDefinitionDescriptor;
import com.sun.enterprise.deployment.JndiNameEnvironment;
import com.sun.enterprise.deployment.LifecycleCallbackDescriptor;
import com.sun.enterprise.deployment.MailSessionDescriptor;
import com.sun.enterprise.deployment.MessageDestinationReferenceDescriptor;
import com.sun.enterprise.deployment.ResourceDescriptor;
import com.sun.enterprise.deployment.ResourceEnvReferenceDescriptor;
import com.sun.enterprise.deployment.ResourceReferenceDescriptor;
import com.sun.enterprise.deployment.ServiceReferenceDescriptor;
import com.sun.enterprise.deployment.node.runtime.RuntimeBundleNode;
import com.sun.enterprise.deployment.util.DOLUtils;
import com.sun.enterprise.deployment.xml.TagNames;
import com.sun.enterprise.deployment.xml.WebServicesTagNames;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.function.Function;

import org.glassfish.api.naming.SimpleJndiName;
import org.glassfish.deployment.common.Descriptor;
import org.glassfish.deployment.common.JavaEEResourceType;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.internal.api.Globals;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.Attributes;

import static com.sun.enterprise.deployment.util.DOLUtils.ADD_DESCRIPTOR_FAILURE;
import static com.sun.enterprise.deployment.util.DOLUtils.INVALID_DESC_MAPPING;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.WARNING;
import org.glassfish.config.support.TranslatedConfigView;

/**
 * Superclass of all Nodes implementation
 * XMLNode implementation represents all the DOL classes responsible for
 * handling  the XML deployment descriptors. These nodes are called by the
 * SAX parser when reading and are constructed to build the DOM tree for
 * saving the XML files.
 *
 * XMLNode are organized like a tree with one root XMLNode (which
 * implement the RootXMLNode interface) and sub XMLNodes responsible
 * for handling subparts of the XML documents. Sub XMLNodes register
 * themselves to their parent XMLNode as handlers for a particular XML
 * subtag of the tag handled by the parent XMLNode
 *
 * Each XMLNode is therefore associated with a xml tag (located anywhere
 * in the tree of tags as defined by the DTD). It owns the responsibility for
 * reading and writing the tag, its attributes and all subtags (by using
 * delegation to sub XMLNode if necessary).
 *
 * @param <T> Deployment {@link Descriptor} type.
 * @author Jerome Dochez
 */
public abstract class DeploymentDescriptorNode<T extends Descriptor> implements XMLNode<T>  {

    private static final Logger LOG = DOLUtils.getLogger();
    private static final String QNAME_SEPARATOR = ":";

    private static final Map<Class<?>, Function<String, Object>> ALLOWED_DESCRIPTOR_INFO_CONVERSIONS = Map.of(
        String.class, String::valueOf, SimpleJndiName.class, SimpleJndiName::of, int.class, Integer::valueOf,
        long.class, Long::valueOf, boolean.class, Boolean::valueOf);

    protected final ServiceLocator serviceLocator = Globals.getDefaultHabitat();

    /***
     * The handlers is the map of XMLNodes registered for handling sub xml tags of the current
     * XMLNode
     */
    protected Hashtable<String, Class<?>> handlers;

    /**
     * Map of add methods declared on the descriptor class to add sub descriptors extracted
     * by the handlers registered above. The key for the table is the xml root tag for the
     * descriptor to be added, the value is the method name to add such descriptor to the
     * current descriptor.
     */
    private Hashtable<String, String> addMethods;

    /** Each node is associated with a XML tag it is handling */
    private XMLElement xmlTag;

    /**
     * Parent node in the XML Nodes implementation tree we create to map to the XML
     * tags of the XML document
     */
    private XMLNode<?> parentNode;

    /**
     * Default descriptor associated with this node, some sub nodes which
     * relies on the dispatch table don't really need to know the actual
     * type of the descriptor they deal with since they are populated through
     * reflection method calls
     */
    protected T abstractDescriptor;

    /** Creates new DeploymentDescriptorNode */
    public DeploymentDescriptorNode() {
        registerElementHandler(new XMLElement(TagNames.DESCRIPTION), LocalizedInfoNode.class);
    }


    /**
     * @return the descriptor instance to associate with this XMLNode
     */
    @Override
    public T getDescriptor() {
        if (abstractDescriptor == null) {
            abstractDescriptor = createDescriptor();
        }
        return abstractDescriptor;
    }


    protected T createDescriptor() {
        return DescriptorFactory.getDescriptor(getXMLPath());
    }


    /**
     * Adds  a new DOL descriptor instance to the descriptor instance associated with
     * this XMLNode
     *
     * @param descriptor the new descriptor
     */
    @Override
    public void addDescriptor(Object descriptor) {
        if (getParentNode() == null) {
            throw new RuntimeException("Cannot add " + descriptor + " to " + toString());
        }
        getParentNode().addDescriptor(descriptor);
    }

    /**
     * Adds a new DOL descriptor instance to the descriptor associated with this XMLNode
     *
     * @param node the sub-node adding the descriptor;
     */
    protected void addNodeDescriptor(DeploymentDescriptorNode node) {
        // if there is no descriptor associated with this class, the addDescriptor should implement
        // the fate of this new descriptor.
        if (getDescriptor() == null) {
            addDescriptor(node.getDescriptor());
            return;
        }
        String xmlRootTag = node.getXMLRootTag().getQName();
        if (addMethods == null || !addMethods.containsKey(xmlRootTag)) {
            addDescriptor(node.getDescriptor());
            return;
        }
        try {
            String methodName = addMethods.get(xmlRootTag);
            final Class<?> parameterType = node.getDescriptor().getClass();
            Method toInvoke = getCompatibleMethod(getDescriptor().getClass(), methodName, parameterType);
            toInvoke.invoke(getDescriptor(), node.getDescriptor());
        } catch (Exception t) {
            // We report the error but we continue loading, this will allow the verifier to
            // catch these errors or to register an error handler for notification
            LOG.log(ERROR, ADD_DESCRIPTOR_FAILURE, node.getDescriptor().getClass(), getDescriptor().getClass());
            LOG.log(ERROR, "Cause:", t);
        }
    }

    /**
     * set the parent node for the current instance.
     */
    public void setParentNode(XMLNode<?> parentNode) {
        this.parentNode = parentNode;
    }

    /**
     * @return the parent node of the current instance
     */
    @Override
    public XMLNode<?> getParentNode() {
        return parentNode;
    }


    /**
     * @return the root node of the current instance
     */
    @Override
    public XMLNode<?> getRootNode() {
        XMLNode<?> parent = this;
        while (parent.getParentNode() != null) {
            parent = parent.getParentNode();
        }
        return parent;
    }


    /**
     * register a new XMLNode handler for a particular XML tag.
     *
     * @param element XMLElement is the XML tag this XMLNode will handle
     * @param handler the class implemenenting the XMLNode interface
     */
    protected final void registerElementHandler(XMLElement element, Class<?> handler) {
        if (handlers == null) {
            handlers = new Hashtable<>();
        }
        handlers.put(element.getQName(), handler);
    }

    /**
     * register a new XMLNode handler for a particular XML tag.
     *
     * @param element XMLElement is the XML tag this XMLNode will handle
     * @param handler the class implemenenting the XMLNode interface
     * @param addMethodName is the method name for adding the descriptor
     * extracted by the handler node to the current descriptor
     */
    public final void registerElementHandler(XMLElement element, Class<?> handler, String addMethodName) {
        registerElementHandler(element, handler);
        if (addMethods == null) {
            addMethods = new Hashtable<>();
        }
        addMethods.put(element.getQName(), addMethodName);
    }

    /**
     * @return the XML tag associated with this XMLNode
     */
    protected XMLElement getXMLRootTag() {
        return xmlTag;
    }

    /**
     * sets the XML tag associated with this XMLNode
     */
    protected void setXMLRootTag(XMLElement element) {
        xmlTag = element;
    }

    /**
     * @return the handler registered for the subtag element of the curent  XMLNode
     */
    @Override
    public XMLNode<?> getHandlerFor(XMLElement element) {
        if (handlers == null) {
            LOG.log(WARNING, "No handler registered in " + this);
            return null;
        }
        Class<?> c = handlers.get(element.getQName());
        if (c == null) {
            LOG.log(WARNING, "No class registered for " + element.getQName() + " in " + this);
            return null;
        }
        LOG.log(DEBUG, "New Handler requested for {0}", c);
        DeploymentDescriptorNode<?> node;
        try {
            node = (DeploymentDescriptorNode<?>) c.getDeclaredConstructor().newInstance();
            node.setParentNode(this);
            node.setXMLRootTag(element);
            // enforces descriptor initialization for some nodes.
            node.getDescriptor();
        } catch(Exception e) {
            LOG.log(WARNING, "Error occurred", e);
            return null;
        }
        return node;
    }

    private Class<?> getExtensionHandler(final XMLElement element) {
        DeploymentDescriptorNode<?> extNode = (DeploymentDescriptorNode<?>) serviceLocator.getService(XMLNode.class,
            element.getQName());
        if (extNode == null) {
            return null;
        }
        return extNode.getClass();
    }


    @Override
    public void startElement(XMLElement element, Attributes attributes) {
        if (!this.getXMLRootTag().equals(element)) {
            return;
        }

        if (attributes.getLength() > 0) {
            for (int i = 0; i < attributes.getLength(); i++) {
                String attrName = attributes.getQName(i);
                String attrValue = TranslatedConfigView.expandApplicationValue(attributes.getValue(i));
                LOG.log(DEBUG, "With attribute {0} and value {1}", attrName, attrValue);
                // we try the setAttributeValue first, if not processed then the setElement
                if (!setAttributeValue(element, new XMLElement(attrName), attrValue)) {
                    setElementValue(new XMLElement(attrName), attrValue);
                }
            }
        }
    }


    /**
     * parsed an attribute of an element
     *
     * @param elementName the element name
     * @param attributeName the attribute name
     * @param value the attribute value
     * @return true if the attribute was processed
     */
    protected boolean setAttributeValue(XMLElement elementName, XMLElement attributeName, String value) {
        // we do not support id attribute for the moment
        return attributeName.getQName().equals(TagNames.ID);
    }

    /**
     * receives notification of the end of an XML element by the Parser
     *
     * @param element the xml tag identification
     * @return true if this node is done processing the XML sub tree
     */
    @Override
    public boolean endElement(XMLElement element) {
        boolean allDone = element.equals(getXMLRootTag());
        if (allDone) {
            postParsing();
            if (getParentNode() != null && getDescriptor() != null) {
                ((DeploymentDescriptorNode) getParentNode()).addNodeDescriptor(this);
            }
        }
        return allDone;
    }

    /**
     * notification of the end of XML parsing for this node
     */
    public void postParsing() {
    }

    /**
     *  @return true if the element tag can be handled by any registered sub nodes of the
     * current XMLNode
     */
    @Override
    public boolean handlesElement(XMLElement element) {
        // Let's iterator over all the statically registered handlers to
        // find one which is responsible for handling the XML tag.
        if (handlers != null) {
            for (Enumeration<String> handlersIterator = handlers.keys();handlersIterator.hasMoreElements();) {
                String subElement = handlersIterator.nextElement();
                if (element.getQName().equals(subElement)) {
                    // record element to node mapping for runtime nodes
                    recordNodeMapping(element.getQName(), handlers.get(subElement));
                    return false;
                }
            }
        }

        // let's now find if there is any dynamically registered handler
        // to handle this XML tag
        Class<?> extHandler = getExtensionHandler(element);
        if (extHandler != null) {
            // if yes, we should add this handler to the table so
            // we don't need to look it up again later and also return
            // false
            registerElementHandler(new XMLElement(element.getQName()), extHandler);
            // record element to node mapping for runtime nodes
            recordNodeMapping(element.getQName(), extHandler);
            return false;
        }

        recordNodeMapping(element.getQName(), this.getClass());
        return true;
    }

    // record element to node mapping
    private void recordNodeMapping(String subElementName, Class<?> handler) {
        XMLNode<?> rootNode = getRootNode();
        if (rootNode instanceof RuntimeBundleNode) {
            ((RuntimeBundleNode) rootNode).recordNodeMapping(getXMLRootTag().getQName(), subElementName, handler);
        }
    }

    /**
     * receives notification of the value for a particular tag
     *
     * @param element the xml element
     * @param value it's associated value
     */
    @Override
    public void setElementValue(XMLElement element, String value) {
        LOG.log(DEBUG, "setElementValue(element={0}, value={1})", element, value);
        T descriptor = getDescriptor();
        if (descriptor == null) {
            throw new IllegalStateException("Descriptor not available in " + this);
        }
        Map<String, String> dispatchTable = getDispatchTable();
        if (dispatchTable == null) {
            throw new IllegalStateException("Method dispatch table not available in " + this);
        }
        String qName = element.getQName();
        String methodName = dispatchTable.get(qName);
        if (methodName == null) {
            // we just ignore these values from the DDs
            LOG.log(WARNING, "Deprecated element {0} with value {1} is ignored for descriptor {2} of node {3}.",
                element, value, descriptor.getClass(), getClass());
            LOG.log(DEBUG, "Helpful stacktrace for the previous warning.", new RuntimeException());
            return;
        }
        try {
            setDescriptorInfo(descriptor, methodName, value);
        } catch (InvocationTargetException e) {
            LOG.log(WARNING, INVALID_DESC_MAPPING, qName, value, descriptor.getClass());
            Throwable t = e.getCause();
            if (t instanceof IllegalArgumentException) {
                // We report the error but we continue loading, this will allow the verifier
                // to catch these errors or to register an error handler for notification
                LOG.log(WARNING, INVALID_DESC_MAPPING, qName, value, descriptor.getClass());
            } else {
                throw new IllegalArgumentException(
                    "Failed " + methodName + " when tried to set '" + value + "' to the descriptor " + descriptor, e);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException(
                "Failed " + methodName + " when tried to set '" + value + "' to the descriptor " + descriptor, e);
        }
    }

    /**
     * all sub-implementation of this class can use a dispatch table to map xml element to
     * method name on the descriptor class for setting the element value.
     *
     * @return the map with the element name as a key, the setter method as a value
     */
    protected Map<String, String> getDispatchTable() {
        // no need to be synchronized for now
        Map<String, String> table =  new HashMap<>();
        table.put(TagNames.DESCRIPTION, "setDescription");
        return table;
    }


    /**
     * Call a setter method on a descriptor with a new value converted automatically
     * to a compatible type.
     *
     * @param target the descriptor to use
     * @param methodName the setter method to invoke
     * @param value the new value for the field in the descriptor
     * @throws ReflectiveOperationException if method failed because of number formatting or method
     *             doesn't exist.
     * @throws InvocationTargetException if the invocation failed for other reason.
     * @deprecated guessing element type
     */
    @Deprecated
    private void setDescriptorInfo(Object target, String methodName, String value) throws ReflectiveOperationException {
        LOG.log(Level.DEBUG, "setDescriptorInfo(target.class={0}, methodName={1}, value={2})", target.getClass(),
            methodName, value);

        ReflectiveOperationException e = new ReflectiveOperationException("Could not find compatible setter.");
        for (Entry<Class<?>, Function<String, Object>> entry : ALLOWED_DESCRIPTOR_INFO_CONVERSIONS.entrySet()) {
            try {
                Method toInvoke = target.getClass().getMethod(methodName, entry.getKey());
                toInvoke.invoke(target, entry.getValue().apply(value));
                // If the call succeeded, we are done.
                return;
            } catch (NumberFormatException nfe) {
                e.addSuppressed(nfe);
            } catch (NoSuchMethodException nsme) {
                e.addSuppressed(nsme);
            }
        }
        throw e;
    }

    /**
     * @return the XPath this XML Node is handling
     */
    @Override
    public String getXMLPath() {
        if (getParentNode() == null) {
            return getXMLRootTag().getQName();
        }
        return getParentNode().getXMLPath() + "/" + getXMLRootTag().getQName();
    }

    /**
     * write the descriptor class to a DOM tree and return it
     *
     * @param parent node in the DOM tree
     * @param descriptor the descriptor to write
     * @return the DOM tree top node
     */
    @Override
    public Node writeDescriptor(Node parent, T descriptor) {
        return writeDescriptor(parent, getXMLRootTag().getQName(), descriptor);
    }


    /**
     * write the descriptor class to a DOM tree and return it
     *
     * @param parent node in the DOM tree
     * @param nodeName name for the root element for this DOM tree fragment
     * @param descriptor the descriptor to write
     * @return the DOM tree top node
     */
    public Node writeDescriptor(Node parent, String nodeName, T descriptor) {
        return appendChild(parent, nodeName);
    }

    /**
     * write all occurrences of the descriptor corresponding to the current
     * node from the parent descriptor to an JAXP DOM node and return it
     *
     * This API will be invoked by the parent node when the parent node
     * writes out a mix of statically and dynamically registered sub nodes.
     *
     * This method should be overriden by the sub classes if it
     * needs to be called by the parent node.
     *
     * @param parent node in the DOM tree
     * @param nodeName the name of the node
     * @param parentDesc parent descriptor of the descriptor to be written
     * @return the JAXP DOM node
     */
    public Node writeDescriptors(Node parent, String nodeName, Descriptor parentDesc) {
        return parent;
    }

    /**
     * write out simple text element based on the node name
     * to an JAXP DOM node and return it
     *
     * This API will be invoked by the parent node when the parent node
     * writes out a mix of statically and dynamically registered sub nodes.
     * And this API is to write out the simple text sub element that the
     * parent node handles itself.
     *
     * This method should be overriden by the sub classes if it
     * needs to be called by the parent node.
     *
     * @param parent node in the DOM tree
     * @param nodeName node name of the node
     * @param parentDesc parent descriptor of the descriptor to be written
     * @return the JAXP DOM node
     */
    public Node writeSimpleTextDescriptor(Node parent, String nodeName, Descriptor parentDesc) {
        return parent;
    }

    /**
     * write out descriptor in a generic way to an JAXP DOM
     * node and return it
     *
     * This API will generally be invoked when the parent node needs to
     * write out a mix of statically and dynamically registered sub nodes.
     *
     * @param node current node in the DOM tree
     * @param nodeName node name of the node
     * @param descriptor the descriptor to be written
     * @return the JAXP DOM node for this descriptor
     */
    public Node writeSubDescriptors(Node node, String nodeName, Descriptor descriptor) {
        XMLNode<?> rootNode = getRootNode();
        if (rootNode instanceof RuntimeBundleNode) {
            // we only support this for runtime xml
            LinkedHashMap<String, Class<?>> elementToNodeMappings = ((RuntimeBundleNode) rootNode)
                .getNodeMappings(nodeName);
            if (elementToNodeMappings != null) {
                Set<Map.Entry<String, Class<?>>> entrySet = elementToNodeMappings.entrySet();
                for (Entry<String, Class<?>> entry : entrySet) {
                    String subElementName = entry.getKey();
                    // skip if it's the element itself and not the subelement
                    if (subElementName.equals(nodeName)) {
                        continue;
                    }
                    Class<?> handlerClass = entry.getValue();
                    if (handlerClass.getName().equals(this.getClass().getName())) {
                        // if this sublement is handled by the current node
                        // it is a simple text element, just append the text
                        // element based on the node name
                        writeSimpleTextDescriptor(node, subElementName, descriptor);
                    } else {
                        // if this sublement is handled by a sub node
                        // write all occurences of this sub node under
                        // the parent node
                        try {
                            DeploymentDescriptorNode<?> subNode = (DeploymentDescriptorNode<?>) handlerClass
                                .getDeclaredConstructor().newInstance();
                            subNode.setParentNode(this);
                            subNode.writeDescriptors(node, subElementName, descriptor);
                        } catch (Exception e) {
                            LOG.log(WARNING, e.getMessage(), e);
                        }
                    }
                }
            }
        }
        return node;
    }

    /**
     * @return the Document for the given node
     */
    static protected Document getOwnerDocument(Node node) {

        if (node instanceof Document) {
            return (Document) node;
        }
        return node.getOwnerDocument();
    }

    /**
     * Append a new element child to the current node
     *
     * @param parent is the parent node for the new child element
     * @param elementName is new element tag name
     * @return the newly created child node
     */
    public static Element appendChild(Node parent, String elementName) {
        Element child = getOwnerDocument(parent).createElement(elementName);
        parent.appendChild(child);
        return child;
    }


    /**
     * Append a new text child
     *
     * @param parent for the new child element
     * @param elementName is the new element tag name
     * @param content object to be printed via {@link String#toString()} to the text content of
     *            the new element
     * @return the newly create child node
     */
    public static Node appendTextChild(Node parent, String elementName, Object content) {
        if (content == null) {
            return null;
        }
        Node child = appendChild(parent, elementName);
        child.appendChild(getOwnerDocument(child).createTextNode(content.toString()));
        return child;
    }


    /**
     * Append a new text child
     *
     * @param parent for the new child element
     * @param elementName is the new element tag name
     * @param text the text for the new element
     * @return the newly create child node
     */
    public static Node appendTextChild(Node parent, String elementName, String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Node child = appendChild(parent, elementName);
        child.appendChild(getOwnerDocument(child).createTextNode(text));
        return child;
    }


    /**
     * Append a new text child
     *
     * @param parent for the new child element
     * @param elementName is the new element tag name
     * @param value the int value for the new element
     * @return the newly create child node
     */
    public static Node appendTextChild(Node parent, String elementName, int value) {
        return appendTextChild(parent, elementName, String.valueOf(value));
    }


    /**
     * Append a new text child even if text is empty
     *
     * @param parent for the new child element
     * @param elementName is the new element tag name
     * @param text the text for the new element
     * @return the newly create child node
     */
    public static Node forceAppendTextChild(Node parent, String elementName, String text) {
        Node child = appendChild(parent, elementName);
        if (text != null && !text.isEmpty()) {
            child.appendChild(getOwnerDocument(child).createTextNode(text));
        }
        return child;
    }

    /**
     * Append a new attribute to an element
     *
     * @param parent for the new child element
     * @param elementName is the new element tag name
     * @param text the text for the new element
     * @result the newly create child node
     */
    public static void setAttribute(Element parent, String elementName, String text) {
        if (text == null || text.length()==0) {
            return;
        }
        parent.setAttribute(elementName, text);
    }

    /**
     * Set a namespace attribute on an element.
     *
     * @param element on which to set attribute
     * @param prefix raw prefix (without "xmlns:")
     * @param namespaceURI namespace URI to which prefix is mapped.
     */
    public static void setAttributeNS(Element element, String prefix, String namespaceURI) {
        String nsPrefix = prefix.isEmpty() ? "xmlns" : ("xmlns" + QNAME_SEPARATOR + prefix);
        element.setAttributeNS("http://www.w3.org/2000/xmlns/", nsPrefix, namespaceURI);
    }

    /**
     * write a list of env entry descriptors to a DOM Tree
     *
     * @param parentNode parent node for the DOM tree
     * @param envEntries the iterator over the descriptors to write
     */
    protected  void writeEnvEntryDescriptors(Node parentNode, Iterator envEntries) {
        if (envEntries == null || !envEntries.hasNext()) {
            return;
        }
        EnvEntryNode subNode = new EnvEntryNode();
        while (envEntries.hasNext()) {
            EnvironmentProperty envProp = (EnvironmentProperty) envEntries.next();
            subNode.writeDescriptor(parentNode, TagNames.ENVIRONMENT_PROPERTY, envProp);
        }
    }

    /**
     * write  the ejb references (local or remote) to the DOM tree
     *
     * @param parentNode parent node for the DOM tree
     * @param refs the set of EjbReferenceDescriptor to write
     */
    protected void writeEjbReferenceDescriptors(Node parentNode, Iterator<EjbReferenceDescriptor> refs) {
        if (refs == null || !refs.hasNext()) {
            return;
        }

        EjbReferenceNode subNode = new EjbReferenceNode();
        // ejb-ref*
        Set<EjbReferenceDescriptor> localRefDescs = new HashSet<>();
        while (refs.hasNext()) {
            EjbReferenceDescriptor ejbRef = refs.next();
            if (ejbRef.isLocal()) {
                localRefDescs.add(ejbRef);
            } else {
                subNode.writeDescriptor(parentNode, TagNames.EJB_REFERENCE, ejbRef);
            }
        }
        // ejb-local-ref*
        for (EjbReferenceDescriptor ejbRef : localRefDescs) {
            subNode.writeDescriptor(parentNode, TagNames.EJB_LOCAL_REFERENCE,ejbRef);
        }
    }


    protected void writeServiceReferenceDescriptors(Node parentNode, Iterator<ServiceReferenceDescriptor> refs) {
        if (refs == null || !refs.hasNext()) {
            return;
        }

        JndiEnvRefNode<ServiceReferenceDescriptor> serviceRefNode = serviceLocator.getService(JndiEnvRefNode.class,
            WebServicesTagNames.SERVICE_REF);
        if (serviceRefNode != null) {
            while (refs.hasNext()) {
                ServiceReferenceDescriptor next = refs.next();
                serviceRefNode.writeDeploymentDescriptor(parentNode, next);
            }
        }
    }

    /**
     * write a list of resource reference descriptors to a DOM Tree
     *
     * @param parentNode parent node for the DOM tree
     * @param resRefs the iterator over the descriptors to write
     */
    protected  void writeResourceRefDescriptors(Node parentNode, Iterator resRefs) {
        if (resRefs == null || !resRefs.hasNext()) {
            return;
        }
        ResourceRefNode subNode = new ResourceRefNode();
        while (resRefs.hasNext()) {
            ResourceReferenceDescriptor aResRef = (ResourceReferenceDescriptor) resRefs.next();
            subNode.writeDescriptor(parentNode, TagNames.RESOURCE_REFERENCE, aResRef);
        }
    }

    /**
     * write a list of resource env reference descriptors to a DOM Tree
     *
     * @param parentNode parent node for the DOM tree
     * @param resRefs the iterator over the descriptors to write
     */
    protected  void writeResourceEnvRefDescriptors(Node parentNode, Iterator<ResourceEnvReferenceDescriptor> resRefs) {
        if (resRefs == null || !resRefs.hasNext()) {
            return;
        }
        ResourceEnvRefNode subNode = new ResourceEnvRefNode();
        while (resRefs.hasNext()) {
            ResourceEnvReferenceDescriptor aResRef = resRefs.next();
            subNode.writeDescriptor(parentNode, TagNames.RESOURCE_ENV_REFERENCE, aResRef);
        }
    }


    /**
     * write a list of message destination reference descriptors to a DOM Tree
     *
     * @param parentNode parent node for the DOM tree
     * @param msgDestRefs the iterator over the descriptors to write
     */
    protected void writeMessageDestinationRefDescriptors(Node parentNode, Iterator msgDestRefs) {
        if (msgDestRefs == null || !msgDestRefs.hasNext()) {
            return;
        }
        MessageDestinationRefNode subNode = new MessageDestinationRefNode();
        while (msgDestRefs.hasNext()) {
            MessageDestinationReferenceDescriptor next = (MessageDestinationReferenceDescriptor) msgDestRefs.next();
            subNode.writeDescriptor(parentNode, TagNames.MESSAGE_DESTINATION_REFERENCE, next);
        }
    }

    /**
     * write a list of entity manager reference descriptors to a DOM Tree
     *
     * @param parentNode parent node for the DOM tree
     * @param entityMgrRefs the iterator over the descriptors to write
     */
    protected void writeEntityManagerReferenceDescriptors(Node parentNode, Iterator entityMgrRefs) {
        if (entityMgrRefs == null || !entityMgrRefs.hasNext()) {
            return;
        }
        EntityManagerReferenceNode subNode = new EntityManagerReferenceNode();
        while (entityMgrRefs.hasNext()) {
            EntityManagerReferenceDescriptor aEntityMgrRef = (EntityManagerReferenceDescriptor)entityMgrRefs.next();
            subNode.writeDescriptor(parentNode, TagNames.PERSISTENCE_CONTEXT_REF, aEntityMgrRef);
        }
    }

    /**
     * write a list of entity manager factory reference descriptors to
     * a DOM Tree
     *
     * @param parentNode parent node for the DOM tree
     * @param entityMgrFactoryRefs the iterator over the descriptors to write
     */
    protected void writeEntityManagerFactoryReferenceDescriptors(Node parentNode,
        Iterator<EntityManagerFactoryReferenceDescriptor> entityMgrFactoryRefs) {
        if (entityMgrFactoryRefs == null || !entityMgrFactoryRefs.hasNext()) {
            return;
        }
        EntityManagerFactoryReferenceNode subNode = new EntityManagerFactoryReferenceNode();
        while (entityMgrFactoryRefs.hasNext()) {
            EntityManagerFactoryReferenceDescriptor aEntityMgrFactoryRef = entityMgrFactoryRefs.next();
            subNode.writeDescriptor(parentNode, TagNames.PERSISTENCE_UNIT_REF, aEntityMgrFactoryRef);
        }
    }

    /**
     * write a list of life-cycle-callback descriptors to a DOM Tree
     *
     * @param parentNode parent node for the DOM tree
     * @param tagName the tag name for the descriptors
     * @param lifecycleCallbacks the iterator over the descriptors to write
     */
    protected void writeLifeCycleCallbackDescriptors(Node parentNode, String tagName,
        Collection<LifecycleCallbackDescriptor> lifecycleCallbacks) {
        if (lifecycleCallbacks == null || lifecycleCallbacks.isEmpty()) {
            return;
        }
        LifecycleCallbackNode subNode = new LifecycleCallbackNode();
        for (LifecycleCallbackDescriptor lcd : lifecycleCallbacks) {
            subNode.writeDescriptor(parentNode, tagName, lcd);
        }
    }

    /**
     * write a list of all descriptors to a DOM Tree
     *
     * @param parentNode parent node for the DOM tree
     * @param descriptorIterator the iterator over the descriptors to write
     */
    protected void writeResourceDescriptors(Node parentNode, Iterator<ResourceDescriptor> descriptorIterator) {
        if (descriptorIterator == null || !descriptorIterator.hasNext()) {
            return;
        }

        DataSourceDefinitionNode dataSourceDefinitionNode = new DataSourceDefinitionNode();
        MailSessionNode mailSessionNode = new MailSessionNode();
        ConnectionFactoryDefinitionNode connectionFactoryDefinitionNode = new ConnectionFactoryDefinitionNode();
        AdministeredObjectDefinitionNode administeredObjectDefinitionNode = new AdministeredObjectDefinitionNode();
        JMSConnectionFactoryDefinitionNode jmsConnectionFactoryDefinitionNode = new JMSConnectionFactoryDefinitionNode();
        JMSDestinationDefinitionNode jmsDestinationDefinitionNode = new JMSDestinationDefinitionNode();

        while (descriptorIterator.hasNext()) {
            ResourceDescriptor descriptor = descriptorIterator.next();

            if (descriptor.getResourceType().equals(JavaEEResourceType.DSD)) {
                DataSourceDefinitionDescriptor next = (DataSourceDefinitionDescriptor) descriptor;
                dataSourceDefinitionNode.writeDescriptor(parentNode, TagNames.DATA_SOURCE, next);
            } else if (descriptor.getResourceType().equals(JavaEEResourceType.MSD)) {
                MailSessionDescriptor next = (MailSessionDescriptor) descriptor;
                mailSessionNode.writeDescriptor(parentNode, TagNames.MAIL_SESSION, next);
            } else if (descriptor.getResourceType().equals(JavaEEResourceType.CFD)) {
                ConnectionFactoryDefinitionDescriptor next = (ConnectionFactoryDefinitionDescriptor) descriptor;
                connectionFactoryDefinitionNode.writeDescriptor(parentNode, TagNames.CONNECTION_FACTORY, next);
            } else if (descriptor.getResourceType().equals(JavaEEResourceType.AODD)) {
                AdministeredObjectDefinitionDescriptor next = (AdministeredObjectDefinitionDescriptor) descriptor;
                administeredObjectDefinitionNode.writeDescriptor(parentNode, TagNames.ADMINISTERED_OBJECT, next);
            } else if (descriptor.getResourceType().equals(JavaEEResourceType.JMSCFDD)) {
                JMSConnectionFactoryDefinitionDescriptor next = (JMSConnectionFactoryDefinitionDescriptor) descriptor;
                jmsConnectionFactoryDefinitionNode.writeDescriptor(parentNode, TagNames.JMS_CONNECTION_FACTORY, next);
            } else if (descriptor.getResourceType().equals(JavaEEResourceType.JMSDD)) {
                JMSDestinationDefinitionDescriptor next = (JMSDestinationDefinitionDescriptor) descriptor;
                jmsDestinationDefinitionNode.writeDescriptor(parentNode, TagNames.JMS_DESTINATION, next);
            }
        }
    }

    /**
     * writes iocalized descriptions (if any) to the DOM node
     */
    protected static void writeLocalizedDescriptions(Node node, Descriptor desc) {
        LocalizedInfoNode localizedNode = new LocalizedInfoNode();
        localizedNode.writeLocalizedMap(node, TagNames.DESCRIPTION, desc.getLocalizedDescriptions());
    }

    /**
     * writes jndi environment references group nodes
     */
    protected void writeJNDIEnvironmentRefs(Node node, JndiNameEnvironment descriptor) {

        /*      <xsd:element name="env-entry"
           type="javaee:env-entryType"
           minOccurs="0" maxOccurs="unbounded"/>
         */
        writeEnvEntryDescriptors(node, descriptor.getEnvironmentProperties().iterator());

        /*      <xsd:element name="ejb-ref"
           type="javaee:ejb-refType"
           minOccurs="0" maxOccurs="unbounded"/>
         */
        /*      <xsd:element name="ejb-local-ref"
           type="javaee:ejb-local-refType"
           minOccurs="0" maxOccurs="unbounded"/>
         */
        writeEjbReferenceDescriptors(node, descriptor.getEjbReferenceDescriptors().iterator());

        /*      <xsd:group ref="javaee:service-refGroup"/>
         */
        writeServiceReferenceDescriptors(node, descriptor.getServiceReferenceDescriptors().iterator());

        /*  <xsd:element name="resource-ref"
           type="javaee:resource-refType"
           minOccurs="0" maxOccurs="unbounded"/>
         */
        writeResourceRefDescriptors(node, descriptor.getResourceReferenceDescriptors().iterator());

        /*  <xsd:element name="resource-env-ref"
                   type="javaee:resource-env-refType"
                   minOccurs="0" maxOccurs="unbounded"/>
         */
        writeResourceEnvRefDescriptors(node, descriptor.getResourceEnvReferenceDescriptors().iterator());

        /*      <xsd:element name="message-destination-ref"
                   type="javaee:message-destination-refType"
                   minOccurs="0" maxOccurs="unbounded"/>
         */
        writeMessageDestinationRefDescriptors(node, descriptor.getMessageDestinationReferenceDescriptors().iterator());

        /*      <xsd:element name="persistence-context-ref"
           type="javaee:persistence-context-refType"
           minOccurs="0" maxOccurs="unbounded"/>
         */
        writeEntityManagerReferenceDescriptors(node, descriptor.getEntityManagerReferenceDescriptors().iterator());

        /*      <xsd:element name="persistence-unit-ref"
           type="javaee:persistence-unit-refType"
           minOccurs="0" maxOccurs="unbounded"/>
         */
        writeEntityManagerFactoryReferenceDescriptors(node, descriptor.getEntityManagerFactoryReferenceDescriptors().iterator());

        /*      <xsd:element name="post-construct"
           type="javaee:lifecycle-callbackType"
           minOccurs="0"
           maxOccurs="unbounded"/>
         */
        writeLifeCycleCallbackDescriptors(node, TagNames.POST_CONSTRUCT, descriptor.getPostConstructDescriptors());


        /*      <xsd:element name="pre-destroy"
           type="javaee:lifecycle-callbackType"
           minOccurs="0"
           maxOccurs="unbounded"/>
         */
        writeLifeCycleCallbackDescriptors(node, TagNames.PRE_DESTROY, descriptor.getPreDestroyDescriptors());
    }

    /**
     * Any node can now declare its own namespace. this apply to DDs only
     * when dealing with deployment extensions. Write any declared
     * namespace declaration
     *
     * @param node from which this namespace is declared
     * @param descriptor containing the namespace declaration if any
     */
    protected void addNamespaceDeclaration(Element node, Descriptor descriptor) {

        // declare now all remaining namepace...
        Map<String, String> prefixMapping = descriptor == null ? null : descriptor.getPrefixMapping();
        if (prefixMapping != null) {
            Set<Map.Entry<String, String>> entrySet = prefixMapping.entrySet();
            for (Entry<String, String> entry : entrySet) {
                String prefix = entry.getKey();
                String namespaceURI = entry.getValue();
                setAttributeNS(node, prefix, namespaceURI);
            }
        }
    }

    /**
     * notify of a new prefix mapping used in this document
     */
    @Override
    public void addPrefixMapping(String prefix, String uri) {
        Descriptor descriptor = getDescriptor();
        // FIXME: two LocalizedNode children return null!!!
        if (descriptor != null) {
            descriptor.addPrefixMapping(prefix, uri);
        }
    }

    /**
     * Resolve a QName prefix to its corresponding Namespace URI by
     * searching up node chain starting with child.
     */
    @Override
    public String resolvePrefix(XMLElement element, String prefix) {
        // If prefix is empty string, returned namespace URI
        // is the default namespace.
        return element.getPrefixURIMapping(prefix);
    }

    /**
     * @return namespace URI prefix from qname, where qname is
     * an xsd:QName, or the empty string if there is no prefix.
     *
     * QName ::= (Prefix ':')? LocalPart
     */
    public String getPrefixFromQName(String qname) {
        StringTokenizer tokenizer = new StringTokenizer(qname, QNAME_SEPARATOR);
        return tokenizer.countTokens() == 2 ? tokenizer.nextToken() : "";
    }

    /**
     * @return local part from qname, where qname is an xsd:QName.
     * QName ::= (Prefix ':')? LocalPart
     */
    public String getLocalPartFromQName(String qname) {
        StringTokenizer tokenizer = new StringTokenizer(qname, QNAME_SEPARATOR);
        String localPart = qname;
        if (tokenizer.countTokens() == 2) {
            // skip namespace prefix.
            tokenizer.nextToken();
            localPart = tokenizer.nextToken();
        }
        return localPart;
    }

    public String composeQNameValue(String prefix, String localPart) {
        return prefix == null || prefix.isEmpty() ? localPart : (prefix + QNAME_SEPARATOR + localPart);
    }


    public void appendQNameChild(String elementName, Node parent, String namespaceUri, String localPart, String prefix) {
        if (prefix == null) {
            prefix = elementName + "_ns__";
        }

        String elementValue = composeQNameValue(prefix, localPart);
        Element element = (Element) appendTextChild(parent, elementName, elementValue);

        // Always set prefix mapping on leaf node.  If the DOL was
        // populated from an existing deployment descriptor it does
        // not preserve the original node structure of the XML document,
        // so we can't reliably know what level to place mapping.
        // Alternatively, if we're writing out a descriptor that was created
        // by the deploytool, there is no prefix->namespace information in
        // the first place.
        setAttributeNS(element, prefix, namespaceUri);
    }


    /**
     * First tries the exact match, when no such method exists it tries to find any compatible
     * method of the same name.
     *
     * @return never null, throws runtime exceptions if it is not possible to find the method for
     *         any reason.
     */
    private Method getCompatibleMethod(Class<?> descriptor, String methodName, Class<?> parameter) {
        LOG.log(DEBUG, "getCompatibleMethod(descriptor={0}, methodName={1}, parameter={2})",
            descriptor, methodName, parameter);
        try {
            return descriptor.getMethod(methodName, parameter);
        } catch (NoSuchMethodException e) {
            // ignore
        } catch (SecurityException e) {
            throw new IllegalStateException("Reflection failed - SecurityException", e);
        }

        Method[] methods = descriptor.getMethods();
        for (Method method : methods) {
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterTypes.length != 1) {
                continue;
            }
            if (!method.getName().equals(methodName)) {
                continue;
            }
            Class<?> parameterOfMethod = parameterTypes[0];
            if (parameterOfMethod.isAssignableFrom(parameter)) {
                return method;
            }
        }
        throw new IllegalArgumentException("Reflection failed for descriptor " + descriptor + ", it's method named "
            + methodName + " and parameter " + parameter);
    }
}
