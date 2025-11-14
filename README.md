# Strimzi Stretch Cluster MCS Plugin

Multi-Cluster Services (MCS) networking provider plugin for Strimzi stretch clusters.

## Overview

This plugin implements the `StretchNetworkingProvider` SPI to enable cross-cluster communication using Multi-Cluster Services (MCS) API. It creates ServiceExports that are automatically discovered across clusters using MCS-compatible implementations like:

- **Submariner** - Multi-cluster networking for Kubernetes
- **Cilium Cluster Mesh** - eBPF-based multi-cluster networking

## Prerequisites

### 1. Multi-Cluster Services (MCS) API (REQUIRED)

**This plugin requires the Kubernetes Multi-Cluster Services (MCS) API to be installed in your clusters.**

The ServiceExport CRD is NOT part of Strimzi or this plugin. It must be installed separately from the official MCS API repository.

**Install MCS CRDs:**

```bash
kubectl apply -f https://github.com/kubernetes-sigs/mcs-api/releases/latest/download/mcs-api.yaml
```

**Verify installation:**

```bash
kubectl get crd serviceexports.multicluster.x-k8s.io
kubectl get crd serviceimports.multicluster.x-k8s.io
```

**Official MCS API Repository:** https://github.com/kubernetes-sigs/mcs-api

### 2. MCS Implementation (REQUIRED)

You also need an MCS-compliant networking implementation such as:

- **[Submariner](https://submariner.io/)** - Multi-cluster networking for Kubernetes
- **[Cilium Cluster Mesh](https://docs.cilium.io/en/stable/network/clustermesh/)** - eBPF-based multi-cluster networking
- Other MCS-compliant solutions

Refer to your chosen implementation's documentation for installation instructions.

### 3. Strimzi Cluster Operator

- Strimzi Cluster Operator 0.48.0 (This will change for sure in future) or later
- Kubernetes clusters with MCS API support

## Building

```bash
mvn clean package
```

This creates `target/strimzi-stretch-mcs-plugin-0.1.0-SNAPSHOT.jar`

## Installation

### Understanding the Configuration

To use the MCS plugin for stretch clusters, you need **two sets of configuration**:

#### Part 1: Stretch Cluster Configuration (REQUIRED)

These environment variables tell the operator about your multi-cluster setup:

```yaml
  - name: STRIMZI_CENTRAL_CLUSTER_ID
    value: cluster1
  - name: STRIMZI_REMOTE_KUBE_CONFIG
    value: |
      Remotecluster1.url=<Remote-cluster1-host>:6443
      Remotecluster1.secret=<Remote-cluster1-kubeconfig-secret>
      Remotecluster2.url=<Remote-cluster2-host>:6443
      Remotecluster2.secret=secret-<Remote-cluster2-kubeconfig-secret>
```

**What they do:**
- `STRIMZI_CENTRAL_CLUSTER_ID` → The ID of the central cluster (where operator runs)
- `STRIMZI_REMOTE_KUBE_CONFIG` → Paths to kubeconfig files for remote clusters

**Without these, stretch cluster mode is NOT enabled!**

#### Part 2: MCS Plugin Configuration (REQUIRED for MCS)

These environment variables tell the operator to use the MCS plugin for networking:

```yaml
  - name: STRIMZI_STRETCH_NETWORK_PROVIDER
    value: custom
  - name: STRIMZI_STRETCH_PLUGIN_CLASS_NAME
    value: io.strimzi.plugin.stretch.McsNetworkingProvider
  - name: STRIMZI_STRETCH_PLUGIN_CLASS_PATH
    value: /opt/strimzi/plugins/stretch-mcs/*
```

**What they do:**
- `STRIMZI_STRETCH_NETWORK_PROVIDER=custom` → Use a custom plugin (not built-in)
- `STRIMZI_STRETCH_PLUGIN_CLASS_NAME=...` → The Java class name of the plugin
- `STRIMZI_STRETCH_PLUGIN_CLASS_PATH=...` → Where to find the plugin JAR

**Plus:** You need to mount the plugin JAR as a volume.

### Option A: Simple Installation (Using kubectl/oc commands)

**Easiest way for OpenShift/Kubernetes:**

```bash
# 1. Create ConfigMap with plugin JAR
kubectl create configmap strimzi-stretch-mcs-plugin \
  --from-file=target/strimzi-stretch-mcs-plugin-0.1.0-SNAPSHOT.jar \
  -n <Namespace>


# 4. Mount the plugin ConfigMap
kubectl set volume deployment/strimzi-cluster-operator \
  --add --name=stretch-mcs-plugin \
  --type=configmap \
  --configmap-name=strimzi-stretch-mcs-plugin \
  --mount-path=/opt/strimzi/plugins/stretch-mcs \
  -n <Namespace>

# Done! Operator will restart automatically
```

### Option B: Manual YAML Configuration

If you prefer to edit the deployment YAML directly:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: strimzi-cluster-operator
  namespace: strimzi
spec:
  template:
    spec:
      containers:
      - name: strimzi-cluster-operator
        env:
        # Part 1: Stretch cluster configuration (REQUIRED)
        - name: STRIMZI_CENTRAL_CLUSTER_ID
          value: cluster1
        - name: STRIMZI_REMOTE_KUBE_CONFIG
          value: |
            Remotecluster1.url=<Remote-cluster1-host>:6443
            Remotecluster1.secret=<Remote-cluster1-kubeconfig-secret>
            Remotecluster2.url=<Remote-cluster2-host>:6443
            Remotecluster2.secret=secret-<Remote-cluster2-kubeconfig-secret>
        
        # Part 2: MCS plugin configuration (REQUIRED for MCS)
        - name: STRIMZI_STRETCH_NETWORK_PROVIDER
          value: custom
        - name: STRIMZI_STRETCH_PLUGIN_CLASS_NAME
          value: io.strimzi.plugin.stretch.McsNetworkingProvider
        - name: STRIMZI_STRETCH_PLUGIN_CLASS_PATH
          value: /opt/strimzi/plugins/stretch-mcs/*
        
        volumeMounts:
        - name: stretch-mcs-plugin
          mountPath: /opt/strimzi/plugins/stretch-mcs
      
      volumes:
      - name: stretch-mcs-plugin
        configMap:
          name: strimzi-stretch-mcs-plugin
```

**Summary:** 5 environment variables (2 for stretch cluster + 3 for MCS plugin) + 1 volume mount.

### Using Built-in Providers

If you use **NodePort** or **LoadBalancer** (built-in providers), you only need **Part 1** configuration:

```yaml
  - name: STRIMZI_CENTRAL_CLUSTER_ID
    value: cluster1
  - name: STRIMZI_REMOTE_KUBE_CONFIG
    value: |
      Remotecluster1.url=<Remote-cluster1-host>:6443
      Remotecluster1.secret=<Remote-cluster1-kubeconfig-secret>
      Remotecluster2.url=<Remote-cluster2-host>:6443
      Remotecluster2.secret=secret-<Remote-cluster2-kubeconfig-secret>
  - name: STRIMZI_STRETCH_NETWORK_PROVIDER
    value: nodeport
```

## Usage

Once installed, create a Kafka cluster with stretch cluster annotations:

```yaml
apiVersion: kafka.strimzi.io/v1beta2
kind: Kafka
metadata:
  name: my-cluster
  annotations:
    strimzi.io/enable-stretch-cluster: "true"
spec:
  kafka:
    version: 3.8.0
    listeners:
      - name: replication
        port: 9091
        type: internal
        tls: true
  ........
  ........
---
apiVersion: kafka.strimzi.io/v1beta2
kind: KafkaNodePool
metadata:
  name: broker-cluster1
  labels:
    strimzi.io/cluster: my-cluster
  annotations:
    strimzi.io/stretch-cluster-alias: cluster1
spec:
  replicas: 3
  roles:
    - broker
  storage:
    type: jbod
    volumes:
    - id: 0
      type: persistent-claim
      size: 100Gi
---
apiVersion: kafka.strimzi.io/v1beta2
kind: KafkaNodePool
metadata:
  name: broker-cluster2
  labels:
    strimzi.io/cluster: my-cluster
  annotations:
    strimzi.io/stretch-cluster-alias: cluster2
spec:
  replicas: 3
  roles:
    - broker
  storage:
    type: jbod
    volumes:
    - id: 0
      type: persistent-claim
      size: 100Gi
```

## How It Works

1. **Service Creation**: Creates ClusterIP services for each Kafka pod
2. **ServiceExport**: Creates ServiceExport resources to make services discoverable across clusters
3. **DNS Resolution**: Uses MCS DNS format: `<service>.<namespace>.svc.clusterset.local`
4. **Cross-Cluster Communication**: MCS implementation handles routing between clusters

## Configuration

No additional configuration required. The plugin uses the MCS API automatically.

## Troubleshooting

### Check ServiceExports

```bash
kubectl get serviceexports -n <namespace>
```

### Check MCS DNS

```bash
# From a pod in cluster1
nslookup my-cluster-kafka-0.my-cluster-kafka-brokers.<namespace>.svc.clusterset.local
```

### Check Plugin Loading

```bash
kubectl logs deployment/strimzi-cluster-operator -n strimzi | grep "MCS"
```

Expected output:
```
INFO: Loading custom networking provider: io.strimzi.plugin.stretch.McsNetworkingProvider from /opt/strimzi/plugins/stretch-mcs/*
INFO: Successfully loaded custom provider: mcs (io.strimzi.plugin.stretch.McsNetworkingProvider)
INFO: ServiceExport CRD detected - MCS API is installed
INFO: MCS provider initialized with clustersetDomain=clusterset.local, requireNamespaceSameness=true
INFO: Provider 'mcs' initialized successfully
```

### MCS CRD Not Installed

If you see this error:

```
ERROR: ServiceExport CRD is not installed. The MCS (Multi-Cluster Services) API must be installed separately. Please install from: https://github.com/kubernetes-sigs/mcs-api
```

This means the MCS API CRDs are not installed. Install them using:

```bash
kubectl apply -f https://github.com/kubernetes-sigs/mcs-api/releases/latest/download/mcs-api.yaml
```

## Contributing

Thank you for your interest in contributing! We welcome contributions from the community.

### Code of Conduct

This project follows the [CNCF Code of Conduct](https://github.com/cncf/foundation/blob/master/code-of-conduct.md).

### How to Contribute

#### Reporting Issues

- Check existing issues before creating a new one
- Provide clear reproduction steps
- Include relevant logs and configuration
- Specify Strimzi version, Kubernetes version, and MCS implementation

#### Submitting Pull Requests

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/my-feature`)
3. Make your changes
4. Add tests if applicable
5. Ensure code compiles (`mvn clean package`)
6. Commit with clear messages
7. Push to your fork
8. Create a Pull Request

#### Code Style

- Follow existing code style
- Use meaningful variable names
- Add JavaDoc for public methods
- Keep methods focused and small
- Handle errors gracefully

#### Testing

- Add unit tests for new functionality
- Test with real Kubernetes clusters when possible
- Verify ServiceExports are created correctly
- Test cross-cluster communication

## Development

### Project Structure

```
strimzi-stretch-mcs-plugin/
├── pom.xml
├── README.md
└── src/
    └── main/
        └── java/
            └── io/
                └── strimzi/
                    └── plugin/
                        └── stretch/
                            └── McsNetworkingProvider.java
```

### Dependencies

All dependencies are marked as `provided` since they're available in the Strimzi cluster-operator:
- Strimzi API
- Kubernetes Client
- Vert.x
- Log4j

### Building for Development

```bash
# Build
mvn clean package

# Copy to test environment
kubectl cp target/strimzi-stretch-mcs-plugin-0.1.0-SNAPSHOT.jar \
  strimzi/strimzi-cluster-operator-xxx:/opt/strimzi/plugins/stretch-mcs/
```

## Questions and Support

- **GitHub Issues**: For bugs and feature requests

## License

Apache License 2.0
