/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.plugin.stretch;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceList;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;

import java.util.Map;

/**
 * Helper class for working with ServiceExport resources from the
 * Kubernetes Multi-Cluster Services (MCS) API.
 * 
 * <p>ServiceExport CRD must be installed separately from the official
 * MCS API repository: https://github.com/kubernetes-sigs/mcs-api
 * 
 * <p>This helper uses Fabric8's GenericKubernetesResource to interact
 * with ServiceExport resources without requiring custom model classes.
 */
public class ServiceExportHelper {
    
    /** MCS API version for ServiceExport. */
    private static final String API_VERSION = "multicluster.x-k8s.io/v1alpha1";
    
    /** Kubernetes kind for ServiceExport. */
    private static final String KIND = "ServiceExport";
    
    /** Plural name for ServiceExport resources. */
    private static final String PLURAL = "serviceexports";
    
    /** Kubernetes client for API operations. */
    private final KubernetesClient client;
    
    /**
     * Creates a new ServiceExportHelper.
     *
     * @param client Kubernetes client to use for operations
     */
    public ServiceExportHelper(final KubernetesClient client) {
        this.client = client;
    }
    
    /**
     * Get ServiceExport operations for the client.
     *
     * @return Mixed operation for ServiceExport resources
     */
    public MixedOperation<GenericKubernetesResource, 
                          GenericKubernetesResourceList, 
                          Resource<GenericKubernetesResource>> getOperations() {
        return client.genericKubernetesResources(API_VERSION, KIND);
    }
    
    /**
     * Create a ServiceExport resource.
     *
     * @param name Name of the ServiceExport
     * @param namespace Namespace for the ServiceExport
     * @param labels Labels to apply (can be null)
     * @param annotations Annotations to apply (can be null)
     * @return GenericKubernetesResource representing the ServiceExport
     */
    public GenericKubernetesResource create(
            final String name,
            final String namespace,
            final Map<String, String> labels,
            final Map<String, String> annotations) {
        
        GenericKubernetesResource serviceExport = new GenericKubernetesResource();
        serviceExport.setApiVersion(API_VERSION);
        serviceExport.setKind(KIND);
        
        ObjectMeta metadata = new ObjectMeta();
        metadata.setName(name);
        metadata.setNamespace(namespace);
        if (labels != null) {
            metadata.setLabels(labels);
        }
        if (annotations != null) {
            metadata.setAnnotations(annotations);
        }
        
        serviceExport.setMetadata(metadata);
        
        return serviceExport;
    }
    
    /**
     * Get a ServiceExport by name and namespace.
     *
     * @param namespace Namespace of the ServiceExport
     * @param name Name of the ServiceExport
     * @return ServiceExport resource, or null if not found
     */
    public GenericKubernetesResource get(final String namespace, final String name) {
        try {
            return getOperations()
                .inNamespace(namespace)
                .withName(name)
                .get();
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Create or update a ServiceExport.
     *
     * @param serviceExport ServiceExport resource to create or update
     * @return Created or updated ServiceExport
     */
    public GenericKubernetesResource createOrReplace(final GenericKubernetesResource serviceExport) {
        return getOperations()
            .inNamespace(serviceExport.getMetadata().getNamespace())
            .withName(serviceExport.getMetadata().getName())
            .createOrReplace(serviceExport);
    }
    
    /**
     * Delete a ServiceExport.
     *
     * @param namespace Namespace of the ServiceExport
     * @param name Name of the ServiceExport
     * @return true if deleted, false otherwise
     */
    public boolean delete(final String namespace, final String name) {
        try {
            return getOperations()
                .inNamespace(namespace)
                .withName(name)
                .delete()
                .size() > 0;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Check if ServiceExport CRD is installed in the cluster.
     *
     * @return true if CRD is installed, false otherwise
     */
    public boolean isCrdInstalled() {
        try {
            String crdName = PLURAL + "." + API_VERSION.split("/")[0];
            return client.apiextensions().v1()
                .customResourceDefinitions()
                .withName(crdName)
                .get() != null;
        } catch (Exception e) {
            return false;
        }
    }
}
