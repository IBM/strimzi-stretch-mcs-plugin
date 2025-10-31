# Building and Testing the MCS Plugin

## Prerequisites

1. **Strimzi Cluster Operator** must be built first (for SPI dependency)
2. **Java 17** or later
3. **Maven 3.8+**
4. **Kubernetes clusters** with MCS support (for testing)

---

## Building the Plugin

### Step 1: Install Strimzi Cluster Operator Locally

The plugin depends on the Strimzi SPI, so you need to install cluster-operator to your local Maven repository first:

```bash
cd /home/oem/Documents/Work/eventstreams-stretch-cluster

# Install cluster-operator to local Maven repo
mvn clean install -pl cluster-operator -DskipTests -Dcheckstyle.skip=true
```

This makes the SPI available at:
```
~/.m2/repository/io/strimzi/cluster-operator/0.46.0/cluster-operator-0.46.0.jar
```

---

### Step 2: Build the MCS Plugin

```bash
cd /home/oem/Documents/Work/strimzi-stretch-mcs-plugin

# Build the plugin JAR
mvn clean package
```

**Output**: `target/strimzi-stretch-mcs-plugin-0.1.0-SNAPSHOT.jar`

This creates a fat JAR with all dependencies bundled (except provided ones).

---

## Testing the Plugin

### Option 1: Local Testing (Recommended)

#### 1. Start a Local Kubernetes Cluster

```bash
# Using kind
kind create cluster --name test-cluster

# Or using minikube
minikube start
```

#### 2. Install Strimzi Cluster Operator

```bash
cd /home/oem/Documents/Work/eventstreams-stretch-cluster

# Create namespace
kubectl create namespace strimzi

# Install CRDs
kubectl create -f packaging/install/cluster-operator/ -n strimzi

# Build and load operator image
make docker_build
kind load docker-image strimzi/operator:latest --name test-cluster
```

#### 3. Deploy Operator with MCS Plugin

Create a ConfigMap with the plugin:

```bash
kubectl create configmap strimzi-stretch-mcs-plugin \
  --from-file=strimzi-stretch-mcs-plugin-0.1.0-SNAPSHOT.jar=/home/oem/Documents/Work/strimzi-stretch-mcs-plugin/target/strimzi-stretch-mcs-plugin-0.1.0-SNAPSHOT.jar \
  -n strimzi
```

Update the operator deployment:

```yaml
# operator-with-plugin.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: strimzi-cluster-operator
  namespace: strimzi
spec:
  replicas: 1
  selector:
    matchLabels:
      name: strimzi-cluster-operator
  template:
    metadata:
      labels:
        name: strimzi-cluster-operator
    spec:
      serviceAccountName: strimzi-cluster-operator
      containers:
      - name: strimzi-cluster-operator
        image: strimzi/operator:latest
        imagePullPolicy: IfNotPresent
        env:
        # Stretch cluster configuration
        - name: STRIMZI_CENTRAL_CLUSTER_ID
          value: cluster1
        - name: STRIMZI_REMOTE_KUBE_CONFIG
          value: cluster2=/path/to/kubeconfig2
        
        # MCS Plugin configuration
        - name: STRIMZI_STRETCH_NETWORK_PROVIDER
          value: custom
        - name: STRIMZI_STRETCH_PLUGIN_CLASS_NAME
          value: io.strimzi.plugin.stretch.McsNetworkingProvider
        - name: STRIMZI_STRETCH_PLUGIN_CLASS_PATH
          value: /opt/strimzi/plugins/stretch-mcs/*
        
        # Other required env vars
        - name: STRIMZI_NAMESPACE
          valueFrom:
            fieldRef:
              fieldPath: metadata.namespace
        
        volumeMounts:
        - name: stretch-mcs-plugin
          mountPath: /opt/strimzi/plugins/stretch-mcs
      
      volumes:
      - name: stretch-mcs-plugin
        configMap:
          name: strimzi-stretch-mcs-plugin
```

Apply:
```bash
kubectl apply -f operator-with-plugin.yaml
```

#### 4. Verify Plugin Loading

```bash
# Check operator logs
kubectl logs deployment/strimzi-cluster-operator -n strimzi | grep -i mcs

# Expected output:
# INFO: Loading custom networking provider: io.strimzi.plugin.stretch.McsNetworkingProvider from /opt/strimzi/plugins/stretch-mcs/*
# INFO: Successfully loaded custom provider: mcs (io.strimzi.plugin.stretch.McsNetworkingProvider)
# INFO: Provider 'mcs' initialized successfully
```

#### 5. Deploy Test Kafka Cluster

```yaml
# test-kafka.yaml
apiVersion: kafka.strimzi.io/v1beta2
kind: Kafka
metadata:
  name: test-cluster
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
---
apiVersion: kafka.strimzi.io/v1beta2
kind: KafkaNodePool
metadata:
  name: broker
  labels:
    strimzi.io/cluster: test-cluster
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
      size: 10Gi
```

```bash
kubectl apply -f test-kafka.yaml -n strimzi
```

#### 6. Verify MCS Resources

```bash
# Check ServiceExports created by plugin
kubectl get serviceexports -n strimzi

# Check Services
kubectl get services -n strimzi | grep kafka

# Check Kafka status
kubectl get kafka test-cluster -n strimzi -o yaml
```

---

### Option 2: Integration Testing (Advanced)

Create a test class:

```bash
mkdir -p src/test/java/io/strimzi/plugin/stretch
```

```java
// src/test/java/io/strimzi/plugin/stretch/McsNetworkingProviderTest.java
package io.strimzi.plugin.stretch;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class McsNetworkingProviderTest {
    
    @Test
    void testProviderName() {
        McsNetworkingProvider provider = new McsNetworkingProvider();
        assertEquals("mcs", provider.getProviderName());
    }
    
    @Test
    void testDnsNameGeneration() {
        McsNetworkingProvider provider = new McsNetworkingProvider();
        String dns = provider.generateServiceDnsName("my-ns", "my-service", "cluster1");
        assertEquals("my-service.my-ns.svc.clusterset.local", dns);
    }
}
```

Add test dependencies to `pom.xml`:

```xml
<dependencies>
    <!-- Test dependencies -->
    <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter</artifactId>
        <version>5.10.0</version>
        <scope>test</scope>
    </dependency>
</dependencies>
```

Run tests:
```bash
mvn test
```

---

## Troubleshooting

### Issue: Plugin class not found

**Symptom**:
```
ERROR: Custom networking provider class not found: 'io.strimzi.plugin.stretch.McsNetworkingProvider'
```

**Solution**:
1. Verify JAR is in ConfigMap:
   ```bash
   kubectl describe configmap strimzi-stretch-mcs-plugin -n strimzi
   ```

2. Verify volume mount:
   ```bash
   kubectl exec deployment/strimzi-cluster-operator -n strimzi -- ls -la /opt/strimzi/plugins/stretch-mcs/
   ```

3. Check JAR contents:
   ```bash
   jar tf target/strimzi-stretch-mcs-plugin-0.1.0-SNAPSHOT.jar | grep McsNetworkingProvider
   ```

---

### Issue: SPI not found during build

**Symptom**:
```
package io.strimzi.operator.cluster.stretch.spi does not exist
```

**Solution**:
Install cluster-operator to local Maven repo first:
```bash
cd /home/oem/Documents/Work/eventstreams-stretch-cluster
mvn clean install -pl cluster-operator -DskipTests
```

---

### Issue: ServiceExports not created

**Symptom**:
```bash
kubectl get serviceexports -n strimzi
# No resources found
```

**Solution**:
1. Check MCS API is installed:
   ```bash
   kubectl api-resources | grep serviceexport
   ```

2. Check operator logs for errors:
   ```bash
   kubectl logs deployment/strimzi-cluster-operator -n strimzi | grep -i error
   ```

3. Verify MCS implementation (Submariner/Cilium) is running

---

## Development Workflow

### 1. Make Changes

Edit `src/main/java/io/strimzi/plugin/stretch/McsNetworkingProvider.java`

### 2. Rebuild

```bash
mvn clean package
```

### 3. Update ConfigMap

```bash
kubectl delete configmap strimzi-stretch-mcs-plugin -n strimzi

kubectl create configmap strimzi-stretch-mcs-plugin \
  --from-file=strimzi-stretch-mcs-plugin-0.1.0-SNAPSHOT.jar=target/strimzi-stretch-mcs-plugin-0.1.0-SNAPSHOT.jar \
  -n strimzi
```

### 4. Restart Operator

```bash
kubectl rollout restart deployment/strimzi-cluster-operator -n strimzi
```

### 5. Verify

```bash
kubectl logs deployment/strimzi-cluster-operator -n strimzi -f
```

---

## CI/CD Integration

### GitHub Actions Example

```yaml
# .github/workflows/build.yml
name: Build MCS Plugin

on:
  push:
    branches: [ main ]
  pull_request:
    branches: [ main ]

jobs:
  build:
    runs-on: ubuntu-latest
    
    steps:
    - uses: actions/checkout@v3
    
    - name: Set up JDK 17
      uses: actions/setup-java@v3
      with:
        java-version: '17'
        distribution: 'temurin'
    
    - name: Cache Maven packages
      uses: actions/cache@v3
      with:
        path: ~/.m2
        key: ${{ runner.os }}-m2-${{ hashFiles('**/pom.xml') }}
    
    - name: Install Strimzi SPI
      run: |
        git clone https://github.com/strimzi/strimzi-kafka-operator.git
        cd strimzi-kafka-operator
        mvn clean install -pl cluster-operator -DskipTests
    
    - name: Build Plugin
      run: mvn clean package
    
    - name: Upload artifact
      uses: actions/upload-artifact@v3
      with:
        name: mcs-plugin
        path: target/strimzi-stretch-mcs-plugin-*.jar
```

---

## Release Process

### 1. Update Version

```bash
mvn versions:set -DnewVersion=0.1.0
```

### 2. Build Release

```bash
mvn clean package
```

### 3. Create GitHub Release

```bash
gh release create v0.1.0 \
  target/strimzi-stretch-mcs-plugin-0.1.0.jar \
  --title "MCS Plugin v0.1.0" \
  --notes "Initial release"
```

### 4. Publish to Maven Central (Optional)

Add to `pom.xml`:
```xml
<distributionManagement>
    <repository>
        <id>ossrh</id>
        <url>https://oss.sonatype.org/service/local/staging/deploy/maven2/</url>
    </repository>
</distributionManagement>
```

Deploy:
```bash
mvn clean deploy -P release
```

---

## Quick Reference

| Command | Purpose |
|---------|---------|
| `mvn clean package` | Build plugin JAR |
| `mvn test` | Run unit tests |
| `mvn clean install` | Install to local Maven repo |
| `jar tf target/*.jar` | List JAR contents |
| `kubectl logs -f deployment/strimzi-cluster-operator -n strimzi` | Watch operator logs |
| `kubectl get serviceexports -n strimzi` | Check MCS resources |

---

## Next Steps

1. ✅ Build the plugin
2. ✅ Test locally with Strimzi
3. ✅ Verify ServiceExports are created
4. ✅ Test cross-cluster communication
5. ✅ Document any issues
6. ✅ Create GitHub repository
7. ✅ Set up CI/CD
8. ✅ Release v0.1.0
