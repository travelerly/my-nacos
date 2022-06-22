![Nacos_Logo](doc/Nacos_Logo.png)

# Nacos: Dynamic  *Na*ming and *Co*nfiguration *S*ervice

---

nacos：1.4.1

spring-boot：2.3.2.RELEASE

spring-cloud-alibaba：2.2.5.RELEASE

---

consistency 模块缺失 entity 包中的代码，需要下载插件 protoc 手动生成。

解决方案参考：https://www.jianshu.com/p/99c48ca4bb72

---

## 几种常见注册中心的对比

| 对比项           | Zookeeper      | Eureka           | consul         | Nacos               |
| ---------------- | -------------- | ---------------- | -------------- | ------------------- |
| CAP              | CP（强一致性） | AP（最终一致性） | CP（强一致性） | CP/AP（默认 AP）    |
| 一致性算法       | Paxos          | -                | Raft           | Raft(CP)/Distro(AP) |
| 自我保护机制     | 无             | 有               | 无             | 有                  |
| SpringCloud 集成 | 支持           | 支持             | 支持           | 支持                |
| Dubbo 集成       | 支持           | 不支持           | 支持           | 支持                |
| K8s 集成         | 不支持         | 不支持           | 支持           | 支持                |



---

## Nacos 系统架构

<img src="doc/nacos架构.jpeg" alt="nacos 架构" style="zoom:50%;" />

Nacos 内部提供了 Config Service 和 Naming Service，底层由 Nacos Core 提供支持，外层提供 OpenAPI 使用，并提供了 User Console、Admin Console 方便用户使用。

从架构图中可以看出，Nacos 提供了两种服务，一种是用于服务注册、服务发现的 Naming Service，一种是用于配置中心、动态配置的 Config Service，而他们底层均由 core 模块来提供支持。

- Provider APP：服务提供者
- Consumer APP：服务消费者
- OpenAPI：暴露标准 Rest 风格 HTTP 接口，简单易用，方便多语言集成
- Config Service：Nacos 的配置服务
- Naming Service：Nacos 服务注册，服务发现模块
- Consistency Protocol：一致性服务，用来实现 Nacos 集群节点的数据同步，使用的是 Raft 或 Distro 算法
- Nacos Console：易用控制台，做服务管理、配置管理等操作

从 OpenAPI 可以了解到，Nacos 通过提供一系列的 HTTP 接口来提供 Naming 服务和 Config 服务：

服务注册 URI：`/nacos/v1/ns/instance` POST请求

服务取消注册 URI：`/nacos/v1/ns/instance` DELETE 请求

心跳检测 URI：`/nacos/v1/ns/instance/beat` PUT 请求

……

都是遵循 REST API 的风格，Nacos 通过 HTTP 这样无状态的协议来进行 client-server 端的通信。

---

## Nacos 数据模型

<img src="doc/nacos数据模型.jpg" alt="nacos数据模型" style="zoom:50%;" />

<img src="doc/nacos数据模型1.jpg" alt="nacos数据模型" style="zoom:50%;" />

<br>

### Nacos 数据模型

nacos 是多级存储模型，最外层通过 namespace 来实现环境隔离，然后是 group 分组，分组下就是服务，一个服务又可以分为不同的集群，集群中包含多个实例，因此注册表结构为一个 Map，类型是：`Map<String,Map<String,Service>>`，外层 key 是 `namespace_id`，内层 key 是 `group+serviceName`，

Service 内部维护一个 Map，结构是：`Map<Strign,Cluster>`，key 是 `clusterName`，值是集群信息

Cluster 内部维护了一个 Set 集合，元素是 Instance 类型，代表集群中的多个实例。

一个 **namespace** 下可以包含有很多的 **group**，一个 **group** 下可以包含有很多的 **service**。但 **service** 并不是一个简单的微服务提供者，而是一类提供者的集合。**service** 除了包含微服务名称外，还可以包含很多的 **Cluster**，每个 **Cluster** 中可以包含很多的 **Instance** 提供者，**Instance** 才是真正的微服务提供者主机。

```yaml
# nacos 客户端配置示例
spring:
    application:
        name: colin-nacos-consumer
    cloud:
        nacos:
            discovery:
                server-addr: localhost:8848
                # 名称空间，默认为 public
                namespace: my_namespace_id
                # 组名，默认为 DEFAULT_GROUP
                group: my_group
                # 集群名，默认为 DEFAULT
                cluster-name: myCluster
                # 是否是临时实例，默认为 true：是临时实例；false：永久实例
                ephemeral: true
```

<img src="doc/目录位置.jpg" alt="目录位置" style="zoom:50%;" />

**my_group%40%40colin-nacos-consumer@@myCluster**

- %40%40：@@ 符号
- my_group：组名，默认为 DEFAULT_GROUP
- colin-nacos-consumer：微服务名称
- myCluster：集群名称，默认为 DEFAULT

<br>

### Nacos 中服务的定义

在 Nacos 中，服务的定义包括以下几个内容：

- 命名空间(Namespace)：Nacos 数据模型中最顶层、也是包含范围最广的概念，用于在类似环境或租户等需要强制隔离的场景中定义。Nacos 的服务也需要使用命名空间来进行隔离。
- 分组(Group)：Nacos 数据模型中仅次于命名空间的一种隔离概念，区别于命名空间的强制隔离属性，分组属于一个弱隔离的概念，主要用于逻辑区分一些服务场景或不同应用的同名服务，最常用的情况主要是同一个服务的测试分组和生产分组、或者将应用作为分组以防止不同应用提供的服务重名
- 服务名(ServiceName)：该服务实际的名字，一般用于描述该服务提供了某种功能或能力

之所以 Nacos 将服务的定义拆分为命名空间、分组和服务名，除了方便隔离使用场景外，还有方便用户发现唯一服务的优点。在注册中心的实际使用场景上，同一个公司的不同开发者可能会开发出类似作用的服务，如果仅仅使用服务名来做服务的定义和表示的话，容易在一些通用服务上出现冲突，比如登录服务等。

<br>

### 临时实例与持久实例

Naocs 的一个特性是：临时实例和持久实例。在定义上区分临时实例和持久实例的关键是健康检查的方式：

- 临时实例使用客户端上报模式
- 持久实例使用服务端反向探测模式

临时实例需要能够自动摘除不健康实例，而且无需持久化存储实例，那么这种实例就适用于类 Gossip 的协议。

持久实例使用服务端探测的健康检查方式，因为客户端不会上报心跳，那么自然就不能去自动摘除下线的实例。

一些基础的组件，例如数据库、缓存等，这些往往不能上报心跳，这种类型的服务在注册时，就需要作为持久实例进行注册。而上层的业务服务，例如微服务或者 Dubbo 服务，服务的 Provider 端支持添加汇报心跳的逻辑，此时就可以使用动态服务的注册方式。

<br>

**临时实例**与**持久实例**的存储位置与健康检测机制是不同的。

1. **临时实例**：默认情况。服务实例仅会注册在 Nacos 内存中，不会持久化到 Nacos 磁盘。其健康检测机制为 Client 模式，即 Client 主动向 Server 上报其健康状态（属于推模式）。默认心跳间隔为 5s；并且在 15s 内 Server 端未收到 Client 心跳，则会将其标记为“**不健康**”状态；若在 30s 内收到了 Client 心跳，则重新恢复到”**健康**“状态，否则该实例将从 Server 端内存中**自动**清除。**适用于实例的弹性扩容场景**。
2. **持久实例**：服务实例不仅会注册到 Nacos 内存中，同时也会被持久化到 Nacos 磁盘上，其健康检测机制为 Server 模式，即 Server 会主动去检测 Client 的健康状态（属于拉模式）。默认每 20s 检测一次，健康检测失败后服务实例会被标记为“**不健康**”状态，但不会被清除，因为其是持久化在磁盘上了，因此对于”**不健康**“的实例的清除，需要专门进行。

---

## Nacos Client 重要 API

### Instance

代表一个 Nacos Client 主机实例

```java
// 代表一个 Nacos Client 主机实例，继承了 pojo.Instance
public class Instance extends com.alibaba.nacos.api.naming.pojo.Instance implements Comparable{
  // 格式：192.168.1.8#8082#myCluster#my_group@@colin-nacos-provider。ip#端口号#集群名#组名@@服务名
  private String instanceId;

  // 仅标识临时实例的健康状态，"true"：健康；"false"：不健康
  private boolean healthy = true;
  // 仅标识持久实例健康状态，"true"：不健康；"false"：健康。「对临时实例无意义」
  private volatile boolean marked = false;

  // 是否是临时实例，true：临时实例（默认值），false：持久实例
  private boolean ephemeral = true
  // 集群名称
  private String clusterName;
  // 微服务名称，格式：my_group@@colin-nacos-provider
  private String serviceName
}
```

serviceName：并不是简单的微服务名称，其格式：groupId@@微服务名称，表示这是一个微服务名称，之所以要在微服务名称前加上 groupId 作为微服务名称，因为 Nacos 中允许在不同的 group 中存在相同的**微服务名称**的微服务应用，而这些应用提供不同的服务。

<br>

### ServiceInfo

微服务信息

```java
// 微服务名称
private String name;

// 组名
private String groupName

// 一个或多个集群组成，多个集群使用逗号分隔
private String clusters;

// 当前微服务的所有提供者实例列表
private List<Instance> hosts = new ArrayList<Instance>();
```

<br>

### NacosNamingService

可以完成 Client 与 Server 间的通信。例如："注册/取消注册"、"订阅/取消订阅"、"获取 Server 状态"、"获取 Server 中指定的 Instance"……**但心跳功能不是通过此类实现的**。

NacosNamingService 实例最终是通过调用 NamingProxy 实例完成的 client 与 server 之间的通信。

<br>

### HostReactor

```java
// futureMap 是一个缓存 map，其 key 为 groupId@@微服务名称@@clusters，value 是一个定时异步操作对象「ScheduledFuture」
private final Map<String, ScheduledFuture<?>> futureMap = new HashMap<String, ScheduledFuture<?>>();

// serviceInfoMap：客户端本地注册表，key 为 groupId@@微服务名称@@cluster名称，value 为 ServiceInfo。
private final Map<String, ServiceInfo> serviceInfoMap;

// 用于存放当前正在发生变更的服务，key：serviceName，groupId@@微服务名称；value：new Object()，没有实际意义。
// 其就是利用了 map 中 key 的唯一性特征，标记一个服务的 ServiceInfo 发生了变更
private final Map<String, Object> updatingMap;

// 用于处理心跳相关功能
private final BeatReactor beatReactor;

// 用于处理 Client 向 Server 端发送请求
private final NamingProxy serverProxy;
```

---

## Nacos Server 重要 API

Nacos 的服务端就是 nacos-naming 模块，就是一个 springboot 项目

### InstanceController

该类为一个处理器，用于处理 Nacos Client 发送过来的心跳、注册、注销等请求。

Nacos Server 对于 Nacos Client 提交的请求，都是由 Controller 处理的，包含注册、订阅、心跳等请求。这些 Controller 一般是在 nacos-naming 模块下的 com.alibaba.nacos.naming.controllers 包中定义。

<br>

### ~nacos-naming/core/Service

在 Nacos 客户端的一个微服务名称定义的微服务，在 Nacos Server 端是以 Service 实例的形式出现的，其类似于客户端的 ServiceInfo。**即 ServiceInfo 是客户端服务，core/Service 是服务端的服务**。

```java
// 继承了 pojo.Service，实现了 Record、RecordListener
public class Service extends pojo.Service implements Record, RecordListener<Instances>{
  
  // 校验和，是当前 Service 的所有 SCI「'service --> cluster --> instance' model」 信息的字符串拼接。
  private volatile String checksum;
  
  // key 为 clusterName；value 为 Cluster 实例，即存储所有 Instance，包括临时实例和持久实例。
  private Map<String, Cluster> clusterMap = new HashMap<>();
}


public class pojo.Service {
   /**
     * 健康保护阈值。protect threshold.
     * 为了防止因过多实例故障，导致所有流量全部流入剩余实例，继而造成流量压力将剩余实例压垮，形成雪崩效应。
     * 应将健康保护阈值定为一个 0~1 之间的浮点数。
     * 当域名健康实例数占总服务实例数的比例小于该值时，无论实例是否健康，都会将这个实例返回给客户端。
     * 这样虽然损失了一部分流量，但保证了集群中剩余健康实例能正常工作。
     *
     * 与 Eureka 中的保护阈值对比：
     * 1.相同点：都是一个 0-1 的数值，标识健康实例占所有实例的比例。
     * 2.保护方式不同：
     * Eureka:  一旦健康实例比例小于阈值，则不再从注册表中清除不健康的实例；
     * Nacos:   一旦健康实例比例数量大于阈值，则消费者调用到的都是健康实例；
     *          一旦健康实例比例小于阈值，则消费者会从所有实例中进行选择调用，所以有可能会调用到不健康实例，这样可以保护健康的实例不会被压崩溃。
     * 3.范围不同：
     * Eureka: 这个阈值是针对所有的服务实例；
     * Nacos:  这个阈值是针对当前 Service 中的服务实例。
     */
  private float protectThreshold = 0.0F;
}
```

pojo.Service 的 protectThreshold 属性，表示服务端的**保护阈值**。Service 类实现了 RecordListener 接口。这个接口是一个数据监听接口。即 Service 类本身还是一个监听器，用于监听服务数据的变更或删除。

<br>

**Nacos 中的 SCI （Service-Cluster-Instance）模型：**

- 其描述的关系是，一个微服务名称指定的服务 Service，可能是由很多的实例 Instance 提供，这些 Instance 实例可以被划分到多个 Cluster 集群中，每个 Cluster 集群中包含若干个 Instance。所以在 Service 类中我们可以看到其包含一个很重要得集合，就是 Map<String, Cluster> clusterMap，其 key 为 clusterName；value 为 Cluster 。而在 Cluster 类中包含两个重要的集合，持久实例集合与临时实例集合。
- 简单来说，SCI 模型就是，一个 Service 包含很多的 Cluster，而一个 Cluster 包含很多 Instance，这些 Instance 提供的就是 Service 所指的服务。

<br>

**pojo.Service 的保护阈值 protectThreshold，与 Eureka 中的保护阈值对比：**

1. 相同点：都是一个 0~1 的数值，表示健康实例占所有实例的比例
2. 保护方式不同：
    - Eureka：当健康实例占比小于阈值，则会开启保护，将不会再从注册表中清除不健康的实例，是为了等待那些不健康实例能够再次发送心跳，恢复健康状态；Eureka 在任何时候都不会消费不健康的实例。
    - Nacos：
        - 当健康实例占比大于阈值时，消费者是从健康实例中选取调用；
        - 当健康实例占比小于保护阈值，则会开启保护，消费者会从所有实例中选取调用，所以有可能会调用到不健康的实例，通过牺牲消费者权益来达到组我保护的目的，这样可以保护健康的实例不会被压崩溃。
3. 范围不同：
    - Eureka：这个阈值时针对所有微服务
    - Nacos：这个阈值是针对当前 Service 中的服务实例，而不是针对所有的服务实例。即针对当前微服务，而不是所有微服务。

<br>

### RecordListener 接口

是一个数据监听的接口，用于监听指定数据的变更或删除，泛型指定了当前监听器正在监听的数据类型。

```java
public interface RecordListener<T extends Record> {
  
  // 判断当前监听器是否监听指定 key 的数据
  boolean interests(String key);
  
  // 判断当前监听器是否已经不再监听当前指定 key 的数据
  boolean matchUnlistenKey(String key);
  
  // 若当前监听的 key 的数据发生变更，则触发此方法
  void onChange(String key, T value) throws Exception;
  
  // 若当前被监听的 key 被删除，则触发此方法
  void onDelete(String key) throws Exception;
}
```

<br>

### Record 接口

RecordListener 接口的泛型，指定了 RecordListener 该监听器所要监听的实体类型，这个实体类型是一个 Record 接口的子接口。Record 是一个在 Nacos 集群中传输和存储的记录。

<br>

### Cluster

提供某一服务的 Instance 的集群，即隶属于某一 Service 的 Instance 集群。

```java
// 持久实例集合
@JsonIgnore
private Set<Instance> persistentInstances = new HashSet<>();

// 临时实例集合
@JsonIgnore
private Set<Instance> ephemeralInstances = new HashSet<>();

// 隶属的 Service
@JsonIgnore
private Service service;
```

<br>

### ServiceManager

Nacos 中所有 Service 的核心管理者。该类中有很多的方法，这些方法可以完成在 Nacos 集群中相关操作的同步。

```java
// 服务端本地注册表，结构为：Map(namespace, Map(group::serviceName, Service)).
private final Map<String, Map<String, Service>> serviceMap = new ConcurrentHashMap<>();

// 存放的是来自于其它 Server 端的服务状态发生变更的服务
private final LinkedBlockingDeque<ServiceKey> toBeUpdatedServicesQueue = new LinkedBlockingDeque<>(1024 * 1024);

// Server 状态同步器。
private final Synchronizer synchronizer = new ServiceStatusSynchronizer();

// 一致性服务
@Resource(name = "consistencyDelegate")
private ConsistencyService consistencyService;
```

<br>

### Synchronizer 接口

同步器，是当前 Nacos Server 主动发起的同步操作。其包含两个方法，

1. 表示当前 Nacos Server 主动发送自己的 Message 给指定的 Nacos Server；
2. 表示当前 Nacos Server 主动从指定 Nacos Server 获取指定 key 的 Message。

---

## 服务注册

### Nacos 客户端的注册

#### 分析入口：

<img src="doc/nacos 客户端注册流程.png" alt="目录位置" style="zoom: 33%;" />

1. spring-cloud-starter-alibaba-nacos-discovery→spring-cloud-commons→spring.factories→**AutoServiceRegistrationAutoConfiguration.class**→@Autowired AutoServiceRegistration
2. spring-cloud-starter-alibaba-nacos-discovery→spring.factories→NacosServiceRegistryAutoConfiguration

<br>

#### 自动注册原理：

其实就是利用了 Spring 事件机制完成的。Naocs 利用 Spring 的事件机制去实现，就是用 SpringCloud 中对于注册中心的一个标准，该标准就是 springcloud-commons 这个包规定的。其中 **AbstractAutoServiceRegistration**，这个类主要是完成自动注册的一个抽象流程，具体的注册逻辑就需要具体的注册中心自己实现，这个类是一个抽象类，同时也实现了 ApplicationListener 接口，并且接口泛型为 WebServerInitializedEvent，作用就是这个类具有了监听器的功能，监听的事件为 WebServerInitializedEvent，当监听到这个事件的时候，就调用 **onApplicationEvent** 方法，最终会调到 start() 方法，然后继续调用到 register 方法，实现注册。

<br>

#### NacosAutoServiceRegistration 的自动注册

1. **NacosAutoServiceRegistration** 在初始化时，其父类 AbstractAutoServiceRegistration 也被初始化了，
2. AbstractAutoServiceRegistration 实现了 **ApplicationListener**<WebServerInitializedEvent>，即实现了对 web 容器启动初始化的监听，web 服务初始化完成后，最终会执行其 bind 方法
3. Tomcat 启动后会触发监听器 ApplicationListener 调用 onApplicationEvent() 方法，发送事件，即 **AbstractAutoServiceRegistration.onApplicationEvent()**→**bind(event)**→AbstractAutoServiceRegistration.start()→register()→**AbstractAutoServiceRegistration.serviceRegistry.register()**→NacosServiceRegistry.register()→NamingService.registerInstance()→**NacosNamingService.registerInstance(String serviceName, String groupName, Instance instance)**，**即真正的客户端注册是通过 NacosNamingService 调用 registerInstance() 方法完成的。**
4. **即 NacosAutoServiceRegistration 实现了对 web 容器启动初始化的监听，Tomcat 启动后会触发 NacosAutoServiceRegistration 的回调，层层调用，就会执行客户端的注册请求。**

> 若要使得某个注册中心与 Spring Cloud 整合后，完成客户端 Client 的自动注册，那么就需要该注册中心的客户端的依赖实现 AutoServiceRegistrationAutoConfiguration 的规范，确切的说是要自定义一个 Starter，完成 AutoServiceRegistration 实例的创建与注入。
>
> Nacos 客户端是基于 SpringBoot 的自动装配实现的，可以在 nacos-discovery 依赖中找到 nacos 自动装配信息 NacosServiceRegistryAutoConfiguration，它会创建 NacosAutoServiceRegistration，其是 AutoServiceRegistration 的实现类

```java
// 应用启动，加载 NacosServiceRegistryAutoConfiguration
public class NacosServiceRegistryAutoConfiguration {
    // 创建 NacosAutoServiceRegistration，是 AutoServiceRegistration 的实现类
    @Bean
	@ConditionalOnBean(AutoServiceRegistrationProperties.class)
	public NacosAutoServiceRegistration nacosAutoServiceRegistration(
		NacosServiceRegistry registry,
		AutoServiceRegistrationProperties autoServiceRegistrationProperties,
		NacosRegistration registration) {
        /**
         * 应用启动会注入 NacosServiceRegistryAutoConfiguration，就会创建 NacosAutoServiceRegistration
         * NacosAutoServiceRegistration 是 AbstractAutoServiceRegistration 的实现类，其实现了对 web 容器启动初始化的监听
         * Tomcat 启动后，就会触发其 onApplicationEvent() 回调方法的执行
         * 层层调用就会执行 NacosNamingService.registerInstance()，即 Nacos Client 的注册
         */
        return new NacosAutoServiceRegistration(registry,
                autoServiceRegistrationProperties, registration);
	}
}
```

<br>

#### **NacosNamingService**

可以完成 Client 与 Server 间的通信，提供了以下功能：

1. 注册/取消注册
2. 订阅/取消订阅
3. 获取 server 状态
4. 获取 server 中指定的 Instance

<br>

#### Nacos 客户端提交注册请求

##### NacosNamingService#registerInstance()

```java
// Nacos Client 发起注册请求(包括注册与心跳)
@Override
public void registerInstance(String serviceName, String groupName, Instance instance) throws NacosException {
    // 检测超时参数是否异常，心跳超时时间（默认 15s）必须大于心跳周期（默认 15s）
    NamingUtils.checkInstanceIsLegal(instance);
    // 生成格式：groupName@@serviceId，例如：my_group@@colin-nacos-consumer
    String groupedServiceName = NamingUtils.getGroupedName(serviceName, groupName);
    // 判断当前实例是否为临时实例「默认为临时实例」，临时实例基于心跳的方式做健康检测，永久实例则是由 nacos 主动探测实例的状态
    if (instance.isEphemeral()) {
        // 构建心跳信息数据
        BeatInfo beatInfo = beatReactor.buildBeatInfo(groupedServiceName, instance);
        // 临时实例，向服务端发送心跳请求。「定时任务」。beatReactor 内部维护了一个线程池
        beatReactor.addBeatInfo(groupedServiceName, beatInfo);
    }
    /**
     * 向服务端发送注册请求，最终由 NacosProxy 的 registerService 方法处理
     * 提交一个 POST 的注册请求。url：/nacos/v1/ns/instance
     */
    serverProxy.registerService(groupedServiceName, groupName, instance);
}
```

##### 该方法完成两个任务：

1. 客户端向服务端发送心跳请求，相关内容在服务心跳环节记录。
2. 客户端向服务端发送注册请求

<br>

##### Nacos 客户端发送注册请求

**NamingProxy#registerService()**

- 如果 Nacos 指定了连接的 server 地址，则尝试连接这个指定的 server 地址，若连接失败，会尝试连接三次（默认值，可配置），若始终失败，会抛出异常；

- 如果 Nacos 没有指定连接的 server 地址，Nacos 会首次按获取到其配置的所有 server 地址，然后再随机选择一个 server 进行连接，如果连接失败，其会以轮询方式再尝试连接下一台，直到将所有 server 都进行了尝试，如果最终没有任何一台能够连接成功，则会抛出异常；

- Nacos 底层是基于 HTTP 协议完成请求的，是使用 Nacos 自定义的一个 HttpClientRequest「JdkHttpClientRequest」发起请求。JdkHttpClientRequest 实现了对 JDK 中的 HttpURLConnection 的封装。

> Nacos Client 向 Nacos Server 发送的注册、订阅、获取状态等连接请求是通过 NamingService 完成，但是心跳请求不是，心跳是通过 BeatReactor 提交的。而 Nacos Client 向 Nacos Server 发送的所有请求最终都是通过 NamingProxy 完成的提交。
>
> Nacos Client 向 Nacos Server 发送的注册、订阅、获取状态等连接请求，是 NamingProxy 分别提交的 **POST**、**PUT**、**GET** 请求。最终是通过其自研的、封装了 JDK 的 HttpURLConnection 的 HttpClientRequest 发出的请求。

<br>

### Nacos 服务端的注册

#### Naocs 服务端处理注册请求

##### InstanceController#register()

```java
/**
 * 服务端处理客户端注册请求，注册新实例。Register new instance.
 */
@CanDistro
@PostMapping
@Secured(parser = NamingResourceParser.class, action = ActionTypes.WRITE)
public String register(HttpServletRequest request) throws Exception {
    // 从请求域中获取指定属性的值：namespaceId，名称空间，如果获取不到，则返回值为 public
    final String namespaceId = WebUtils
            .optional(request, CommonParams.NAMESPACE_ID, Constants.DEFAULT_NAMESPACE_ID);
    // 从请求域中获取指定属性的值：serviceName，服务名称，如果获取不到会抛异常
    final String serviceName = WebUtils.required(request, CommonParams.SERVICE_NAME);
    // 检查 serviceName 是否合法。「必须以"@@"连接两个字符串」
    NamingUtils.checkServiceNameFormat(serviceName);
    // 将请求参数解析成 Instance 数据，根据解析请求域参数得到对应参数值填充到服务实例对象中
    final Instance instance = parseInstance(request);
    // 注册服务实例，即将 Instance 数据注册到注册表中
    serviceManager.registerInstance(namespaceId, serviceName, instance);
    return "ok";
}
```

Nacos 服务端中大量的使用了 WebUtils 中的两个方法 optional() 与 required()，它们的特点为：

-  optional() ：是从请求方法中获取指定的属性，这个属性可能在请求中不存在，若不存在或其值为 null，则会指定一个默认值
-  required()：是从请求方法中获取指定的属性，这个属性在请求中一定存在，是必须值，所以没有默认值；若属性不存在，则直接抛出异常。

<br>

Nacos 默认会以 AP 的模式将实例注册进注册表中，ServiceManager#registerInstance() 方法完成了以下三步操作：

1. createEmptyService()：初始化这个实例对应的服务对象
2. getService()：从注册表中获取到这个实例对应的服务对象
3. addInstance()：向服务对象中添加这个要注册的实例数据

<br>

###### createEmptyService()

初始化服务对象

1. 尝试从服务端本地注册表中（serviceMap）获取该实例对应的服务的 Service 对象，如果注册表（serviceMap）中没有，说明这个服务是第一次被注册，则创建这个服务对应的 Service 对象，并添加进注册表（serviceMap）中；
2. 如果服务端本地注册表（serviceMap）中存在该服务对象，则直接跳过；

在向注册表中添加服务对象时，做了以下三件事：

1. 把服务对象注册到服务端本地注册表中，此时的服务对象中仅仅保存了 namespaceId 和 serviceName 等信息，与该服务的具体主机 Instance 实例数据无关；
2. 初始化这个 Service 对象，其内部开启了健康检查任务。
3. 因为服务 Service 对象自身实现了监听接口，所以 Service 本身就是一个监听器，利用一致性服务，把 Service 作为监听器添加进 Nacos Server 集群中。监听器的作用就是当这个服务发生了数据变更，例如新增实例或删除实例，就会触发监听器回调对应的监听方法。分别设置了临时实例和持久实例的监听器。

初始化 Service 对象时，Service 对象内部针对临时实例和持久实例分别开启了对应的健康检查任务：

**临时实例**：在 Service 的初始化时开启了清除过期 Instance 实例的定时任务，这个任务延迟 5s 开始，每 5s 执行一次 ，其”清除“操作与”注销“请求进行了合并。由当前 Nacos Server 向自己提交一个 delete 请求，由 Nacos Server 端的”注销“方法进行处理。

> 先判断当前服务是否由当前 Server 节点负责，若当前服务不需要当前 Server 节点负责，则直接结束，反之向下执行。在 Nacos 的 AP 架构中，集群中的每一个节点都会负责分配给自己的服务，例如：服务 A 是分配给节点 X，那么节点 X 在这里需要对服务 A 的实例是否过期下线进行判断，其它节点就无需判断了，最终节点 X 同步这个服务 A 的最新信息给到其它节点即可。
>
> 然后再判断当前服务是否开启了健康检查，默认是开启的，如果没有开启，则直接结束
>
> 接着就获取这个服务所包含的所有实例对象，遍历这些对象，对每一个实例对象中的最近一次心跳续约时间与当前时间进行比较，默认相差超过 15s，就把这个实例的健康状态标记为非健康，这里仅仅修改健康状态为”非健康“，并没有把实例进行下线，此时服务的实例数据就发生了变更，然后会发布一个服务变更的事件，即发布一个 ServiceChangeEvent 事件，PushService 订阅了 ServiceChangeEvent 事件，会回调 onApplicationEvent() 方法进行服务变更的推送，将该服务变更事件推送给订阅了该服务的所有客户端。
>
> 然后接着会再一次遍历读取所有实例，在这一次的遍历中，如果当前时间与该实例最近心跳续约事件相差大于 30s，就对该实例进行真正的下线操作，也就是将这个实例从注册表中删除。具体是当前服务 Service 调用 Nacos 自研的 HttpClient 向自己(Nacos Server) 提交一个 delete 请求，该请求最终会由 Nacos Server 的 InstanceController#deregister() 来处理，即与处理客户端提交的注销请求的处理方式相同。上面用到的 Naocs 自研的 HttpClient 是对 Apache 的 http 异步 client 的封装。

**持久实例**：当前服务 Service 会完成集群的初始化，将其所在的集群 Cluster 中所包含的所有持久实例的心跳任务添加到任务队列 taskQueue 中，由线程池来处理，因为持久实例的健康检测是 Server 端向 Client 端发送心跳。

<br>

###### getService()

从注册表中获取到这个实例对应的服务对象，此处再次查询的目的是检验是否已将服务注册进注册表中，如果此时注册表中没有相关数据，则直接抛出异常

<br>

###### addInstance()

向服务 Service 对象中添加这个要注册的实例数据。

> 再次到注册表中获取当前服务对象，然后给对象加锁（synchronized），使得同一服务下的实例串行操作，在更新实例列表时，是基于异步的线程池来完成的，这个线程池内线程的数量是 1 个。
>
> 其内部方法 addIpAddresses() → updateIpAddresses() 先利用一致性服务，获取远程注册表中该服务的实例数据，再复制一个本地注册表中该服务的实例数据的副本，对比副本和远程数据，也就是对比本地注册表和远程注册表中的数据，将对比后的数据保存在一个临时集合中，这个临时集合就表示”最新实例“集合，对比策略为：
>
> 1. 若本地注册表的副本数据中包含有远程数据，则以本地注册表中数据为准，将其替换掉远程实例数据，并将替换后的实例数据保存进临时集合中
> 2. 若本地注册表的副本数据中不包含远程数据，则以远程数据为准，并保存进临时集合中
>
> 然后遍历这些需要变更(此处为注册)的实例数据，先判断当前服务下是否存在当前遍历到的实例对应的集群，若不存在，则需实例化这个集群，并建立服务、集群和实例之间的关系。(若当前服务是第一次注册，其在之前创建的服务 Service 对象是就已经对集群初始化了，因此不会进入本次操作。)，然后在与”最新实例“集合中的数据做比较，判断依据是实例的 datumKey，其格式为：**ip:port:unknown:clusterName(针对订阅请求)** 或 **ip:unknown:clusterName(这对主动请求)**，即根据 datumKey 判断：
>
> 1. 若当前遍历到的需要注册的实例在”最新实例“集合中存在，则说明本次操作属于实例更新，则继续使用原有的实例 ID 作为 datumKey，并将此实例保存进”最新实例“集合中，即替换掉之前的实例数据；
>
> 2. 若当前遍历到的需要注册的实例不存在于”最新实例“集合中，则说明这个实例属于新注册实例，则根据相应规则生成新的实例 ID，最后再将当前遍历到的需要注册的实例保存进”最新实例“集合中，此时”最新实例“集合中可能即包含原有注册表中的实例数据，也包含本次需要注册的实例数据。
>
> 然后将比对后的数据，也就是这个”最新实例“集合中的数据封装进一个新建的 Instances 对象中，再利用一致性服务，同步到 Nacos 集群的其它节点中。
>
> 对于 AP 模式，具体的一致性操作由 DistroConsistencyServiceImpl 完成，其会向将要更新的数据写入到 Service 中，先将 Instances 数据包装后，添加进一个阻塞队列中，然后通过一个单线程的线程池不断的（无限死循环）从阻塞队列中获取任务，然后取出任务中的数据，获取到 Instance 对应的监听器，也即是获取到 Service，然后回调监听器的 onChange() 方法，即 Service#onChange()，Service 会做一些了比对，最终将实例数据保存进clusterMap 中，即注册的实例 Instance 最终保存进 Service 中了。
>
> 然后通过一致性服务，将数据同步给其它 Nacos 节点。

<br>

#### Nacos 服务端处理注销（删除）请求

**InstanceController.deregister()**

- Nacos Server 对于 Nacos Client 的注销请求，并不是直接从本地注册表中删除这个实例 Instance，而是从所有 Nacos Server 中获取这个服务的所有实例的集合，将这个需要注销的实例 Instance 从集合中删除，然后将这个实例集合剩余的数据，通过一致性服务同步给所有的 Nacos Server。

- 这样不仅更新了本地注册表中的数据，同是也更新了其它所有的  Nacos Server 中注册表中的数据。

<br>

### Nacos 注册相关的总结

**这个注册过程没有直接将注册数据写入到当前  Nacos Server 的注册表中，而是通过与远程 Nacos Server 中的数据做对比，整合数据后，再通过一致性服务注册到所有  Nacos Server 中。**

<br>

**Nacos 服务端在处理客户端的注册请求时，如何保证并发写的安全性？**

> 在注册实例时，会对 Service 对象加锁，不同的 Service 之间本身就不存在并发写的问题，互不影响。而相同的Service 是通过锁来互斥，并且在更新实例列表时，是基于异步的线程池来完成的，而线程池内线程的数量为 1。

<br>

**Nacos 服务端如何避免并发读写的冲突？**

> Nacos 服务端在更新实例列表时，会采用 CopyOnWrite 技术，首先将旧实例列表拷贝一份，然后更新拷贝的实例列表，再用更新后的实例列表来覆盖旧实例列表。

<br>

**Nacos 服务端如何应对数十万服务的并发写请求？**

> Nacos 服务端内部会将服务注册的任务放入阻塞队列中，采用线程池异步来完成实例的更新，从而提高并发写能力。

---

## 服务订阅/发现

### Nacos 客户端订阅服务

#### 触发原理

<img src="doc/Nacos客户端订阅服务.jpg" alt="Nacos订阅服务" style="zoom:50%;" />

**Nacos 客户端启动时会创建一个 NacosWatch 类实例，NacosWatch 实现了 SmartLifecycle 接口，应用启动后，会回调其 start() 方法，start() 方法会向 Nacos Server 提交订阅请求。服务订阅，即定时从 Nacos Server 端获取当前服务的所有实例并更新到本地注册表中。**

##### NacosWatch#start()

```java
// NacosWatch 实现了 SmartLifecycle 接口，应用启动后，会回调此方法
@Override
public void start() {
   if (this.running.compareAndSet(false, true)) {
      EventListener eventListener = listenerMap.computeIfAbsent(buildKey(),
            event -> new EventListener() {
               @Override
               public void onEvent(Event event) {
                  if (event instanceof NamingEvent) {
                     List<Instance> instances = ((NamingEvent) event)
                           .getInstances();
                     Optional<Instance> instanceOptional = selectCurrentInstance(
                           instances);
                     instanceOptional.ifPresent(currentInstance -> {
                        resetIfNeeded(currentInstance);
                     });
                  }
               }
            });

      NamingService namingService = nacosServiceManager
            .getNamingService(properties.getNacosProperties());
      try {
         // Nacos Client 向 Nacos Server 提交服务订阅请求（定时从 Server 端获取当前服务的所有实例并更新到本地）
         namingService.subscribe(properties.getService(), properties.getGroup(),
               Arrays.asList(properties.getClusterName()), eventListener);
      }
      catch (Exception e) {
         log.error("namingService subscribe failed, properties:{}", properties, e);
      }

      this.watchFuture = this.taskScheduler.scheduleWithFixedDelay(
            this::nacosServicesWatch, this.properties.getWatchDelay());
   }
}
```

##### Nacos 的服务发现功能有两种模式，

1. 主动拉取模式：客户端主动请求服务端拉取注册的实例，主动拉取的模式比较简单，其实就是客户端发起拉取请求之后，服务端根据请求的内容去服务端注册表中找到对应的注册实例，返回给客户端；
2. 订阅模式：客户端对服务端进行订阅之后，当服务端注册的实例发生变更之后，服务端会主动推送注册实例给客户端。

<br>

#### Nacos 客户端订阅服务

**NacosNamingService#subscribe()**

```java
/**
 * Nacos Client 向 Nacos Server 提交服务订阅请求
 * 此方法可以指定订阅某一服务，并且在监听器中能够监听到服务端中该服务的最新实例
 * 定时从 Nacos Server 端获取当前服务的所有实例并更新到本地
 */
@Override
public void subscribe(String serviceName, String groupName, List<String> clusters, EventListener listener)
        throws NacosException {
    // 通过客户端对象去订阅一个服务，当这个服务发生变更时，就会回调 EventListener 监听器的 onEvent() 方法。
    hostReactor.subscribe(NamingUtils.getGroupedName(serviceName, groupName), StringUtils.join(clusters, ","),
            listener);
}
```

**HostReactor#subscribe()** 方法完成了以下两个操作：

1. InstancesChangeNotifier#registerListener()：给指定的集群注册事件监听器。

2. getServiceInfo()：获取目标服务列表，此方法可以获取到指定服务下指定集群的所有服务实例，并且调用的客户端还会被服务端标记为已订阅客户端。

getServiceInfo() 方法的执行逻辑是先从本地注册表中读取，再根据结果进行选择：

- 如果本地注册表中没有，说明是第一次获取该服务，则从服务端拉取
- 如果本地注册表中有，则先开启定时更新功能，再从本地缓存中读取数据并返回。

从 serviceInfoMap(客户端本地注册表) 中根据服务名称和集群名称去找到对应的 ServiceInfo 对象，这个 ServiceInfo 对象就是保存了对应服务和集群下的所有实例信息，而 serviceInfoMap 就存放了客户端从服务端获取到的所有实例信息，也就是说在客户端这边存储了一份实例信息在内存中。

当客户端第一次去请求这个服务和集群下的所有实例的时候，返回的 ServiceInfo 肯定就是 null，也就是内存中是没有的，需要通过 updateServiceNow 方法从 nacos 服务端中去拉取。

总结来说就是客户端每次都会先从 serviceInfoMap(客户端本地注册表中) 中去获取，如果拿到的 ServiceInfo 为空就需要去请求服务端获取，那么这就需要 serviceInfoMap 中保存的数据与服务端是一致最新的，所以 nacos 是如何保证到这一点的呢？

其实服务端在服务发生改变后都会立刻推送最新的 ServiceInfo 给客户端，客户端拿到最新的 ServiceInfo 之后就更新到 serviceInfoMap 中。还有 getServiceInfo 方法还有个小细节，就是在向服务端发送拉取请求之前会往 updatingMap 中添加一个占位，表示这个服务和集群的实例正在获取中，然后在向服务端发送拉取请求执行完之后才把这个占位从 updatingMap 中移除，也就是说如果第一个线程正在请求服务端获取服务实例，后面的线程再进来的话可能就会来到 else if 分支，在这个分支中其他线程通过 wait 方法进入阻塞的状态，直到第一个线程获取到实例集合数据并缓存到内存中的时候才会被唤醒，或者超时唤醒，默认的超时时间是 5s。

**HostReactor#getServiceInfo()**

**进入到 else if 分支的线程，会被 wait 方法阻塞**

```java
/**
 * 获取目标服务(列表)，订阅服务信息
 * 调用此方法可以获取到指定服务下指定集群的所有服务实例，并且调用的客户端还会被 nacos 服务端视为已订阅客户端，
 * 该方法用于客户端订阅拉取服务的模式
 *
 * 逻辑时先从本地缓存中读，根据结果来进行选择；
 * 1.如果本地缓存中没有，则从服务端拉取；
 * 2.如果本地缓存中有，则先开启定时更新功能，载从本地缓存中读取后返回结果
 *
 * @param serviceName 指定的服务名称
 * @param clusters 指定的集群集合（逗号分隔）
 * @return
 */
public ServiceInfo getServiceInfo(final String serviceName, final String clusters) {

    NAMING_LOGGER.debug("failover-mode: " + failoverReactor.isFailoverSwitch());
    // 构建 key，格式为：微服务名称@@cluster名称，例如：colin-nacos-consumer@@myCluster
    String key = ServiceInfo.getKey(serviceName, clusters);
    if (failoverReactor.isFailoverSwitch()) {
        return failoverReactor.getService(key);
    }

    // 从当前客户端的本地注册表中获取当前服务，即读取本地服务列表的缓存，缓存是一个 Map<String,ServiceInfo>
    ServiceInfo serviceObj = getServiceInfo0(serviceName, clusters);

    // 判断缓存是否存在
    if (null == serviceObj) { // 第一次获取该服务集群对应的实例

        // 本地注册表中没有该服务，则创建一个空的服务「没有任何提供者实例 Instance 的 ServiceInfo」
        serviceObj = new ServiceInfo(serviceName, clusters);
        // 将空服务放入客户端本地注册表中。serviceInfoMap：客户端本地注册表，key 为 groupId@@微服务名称@@cluster名称，value 为 ServiceInfo。
        serviceInfoMap.put(serviceObj.getKey(), serviceObj);

        /**
         * 临时缓存，待更新的服务列表，利用 map 的 key 不能重复的特性；只要有服务名称存在这个缓存中，就表示当前这个服务正在被更新。
         * 准备要更新 serviceName 的服务了，就先将其名称存入临时缓存 map 中。
         * 对该服务进行占位。
         */
        updatingMap.put(serviceName, new Object());

        // 向 nacos 服务端发送请求，获取指定服务的实例数据，并更新本地注册表。（立即更新服务列表）
        updateServiceNow(serviceName, clusters);

        // 更新完毕，将该 serviceName 服务从临时缓存中删除。即删除占位。
        updatingMap.remove(serviceName);

    } else if (updatingMap.containsKey(serviceName)) {
        // 至此说明当前服务正在被更新，即有服务发生变更，在临时缓存中有占位，即缓存中有该服务名称，但需要更新

        if (UPDATE_HOLD_INTERVAL > 0) {
            // hold a moment waiting for update finish
            synchronized (serviceObj) {
                try {
                    /**
                     * 若当前注册表中已经存在该服务，则需要先查看一下临时缓存 map 中是否存在该服务
                     * 若临时缓存 map 中存在该服务，则说明这个服务正在被更新，所以本次操作需阻塞一会，
                     * 直到前一个线程获取到实例集合数据并缓存到内存中的时候才会被唤醒，或者超时唤醒，默认的超时时间是 5s。
                     */
                    serviceObj.wait(UPDATE_HOLD_INTERVAL);
                } catch (InterruptedException e) {
                    NAMING_LOGGER
                            .error("[getServiceInfo] serviceName:" + serviceName + ", clusters:" + clusters, e);
                }
            }
        }
    }

    // 启动定时任务，定时更新本地注册表中当前服务的数据，即开启定时更新服务列表的功能。
    scheduleUpdateIfAbsent(serviceName, clusters);
    // 返回缓存中的服务信息，serviceInfoMap：客户端本地注册表，key 为 微服务名称@@cluster名称，value 为 ServiceInfo。
    return serviceInfoMap.get(serviceObj.getKey());
}
```

HostReactor#updateServiceNow() → HostReactor#updateService()

**在 finally 中解除了其它线程的阻塞状态**

```java
/**
 * 从服务端获取到指定服务下的所有实例，并且当前客户端还会被服务端所绑定作为推送的目标客户端。
 * Update service now.
 *
 * @param serviceName service name 指定的服务端名称
 * @param clusters    clusters 指定的集群集合
 */
public void updateService(String serviceName, String clusters) throws NacosException {
    // 从本地注册表中获取目标服务的数据
    ServiceInfo oldService = getServiceInfo0(serviceName, clusters);
    try {
        /**
         * 基于 ServerProxy 发起远程调用，拉取对应服务和集群下所有实例
         * 向 server 提交一个"GET"请求，获取指定服务所有实例，同时当前客户端还会订阅该指定的服务，返回结果是 JSON 格式
         */
        String result = serverProxy.queryList(serviceName, clusters, pushReceiver.getUdpPort(), false);

        if (StringUtils.isNotEmpty(result)) {
            // 处理查询结果，解析服务端返回的 JSON 格式的 serviceInfo，将其更新到本地注册表中「serviceInfoMap」
            processServiceJson(result);
        }
    } finally {
        if (oldService != null) {
            synchronized (oldService) {
                // 解除上层线程的阻塞。
                oldService.notifyAll();
            }
        }
    }
}
```

最终由 NamingProxy#queryList() 向服务端提交订阅拉取请求，这个请求根据是否指定了服务端，而采用不同的重试机制：

1. 指定了服务端，则直接链接该 Server，并向其提交请求，若链接失败，默认重试三次，重试次数也可根据 REQUEST_DOMAIN_RETRY_COUNT 自定义配置；
2. 未指定服务端，则会遍历所有的服务端 Server，从中随机选择一个进行链接，若向 Server 链接失败，则采用轮询的方式尝试链接下一个 Server，重试次数为 Server 列表的长度。

> NamingProxy#queryList() 方法的第三个参数，需要传入一个 UDP 端口，如果是订阅拉取的话，那么这个参数是大于 0 的，如果是普通拉取的操作，那么这个参数就是 0，即服务端会根据这个参数去判断客户端的请求是订阅拉取，还是普通拉取。
>
> 客户端在获取到最新的服务实例数据之后，交给 processServiceJson() 方法进行处理，最后 updateService 方法在 finally 代码块中，解除了其他线程的阻塞状态。

<br>

**HostReactor#processServiceJson()**

客户端处理服务器返回的数据。服务端返回的数据交给 processServiceJson() 方法处理，此方法将来自于服务端返回或推送的数据更新到当前客户端本地注册表中。该方法有两种情况会被调用：

1. 客户端调用服务端拉取数据(包含订阅拉取和普通拉取)，服务端返回结果后调用此方法；
2. 服务端监听到服务发生变更事件，推送服务最新的实例结果，会调用此方法。

处理分为两个分支：

1. 本地注册表中没有服务端返回的服务，说明是第一次拉取该服务的实例，则将服务端返回的数据直接保存进本地注册表 serviceInfoMap 中。
2. 本地注册表中存在该服务，则说明客户端之前拉取过该服务，此时需要针对不同条件来更新该服务的实例数据。保存完服务数据之后，整理变更的实例数据，发送实例变更事件，该事件的订阅者就会进行监听回调。其事件的发布是将事件添加进一个事件队列中，这是一个异步操作，由于 DefaultPublisher 在初始化时，会开启一个子线程，这个子线程会不断的(死循环)从队列中获取事件进行处理，即遍历所有的事件订阅者，通知他们处理事件。？？？？？？？？？？？？？？？？？？？？？？？？？？？

Naocs 保证客户端注册表中保存的数据是最新的服务信息数据，是通过服务端在服务发生变更的时候，会推送这个服务的最新数据给到客户端，客户端收到推送数据后，对注册表进行更新。更新完客户端本地注册表之后，会通过 InstancesChangeEvent 事件对应的事件发布者去发布一个 InstanceChangeEvent 事件，即发布一个实例变更的事件，发布完之后，该事件发布者对应的事件订阅者就能够进行监听回调。

HostReactor 组件初始化的时候就注册了一个 InstancesChangeEvent 事件的订阅者，对于注册事件，其实例变更事件订阅者为 InstancesChangeNotifier，未完待补充？？？？？？？

<br>

Nacos Client 服务订阅与 Eureka Client 的服务订阅都是从 Server 端下载服务列表。但不同点是：

- Eureka Client 的服务订阅是定时从 Server 端获取**发生变更的服务**的所有实例并更新到本地注册表中；
- Nacos Client 的服务订阅是定时从 Server 端获取**当前服务**的所有实例并更新到本地。

<br>

### Nacos 服务发现

#### Naocs 服务端处理客户端拉取请求

**InstanceController#list()**

服务端接收到客户端的拉取请求后，会根据 UDP 端口来判断是订阅拉取还是普通拉取，从而采用不同的处理逻辑。

- 若是普通拉取，则从服务端注册表中获取到相关数据，在对数据做一些处理后，返回给客户端；
- 若是订阅拉取，除了会从注册表中获取数据并处理后返回以外，还会把发送请求的客户端标记为已订阅客户端，作为可推送的目标客户端添加给提送服务组件 PushService。

> 在 Nacos 的 UDP 通信中，Nacos Server 充当的是 UDP Client，Nacos Client 充当的是 UDP Server，其实是把客户端的 UDP 端口、IP 等信息封装成为一个 PushClient 对象，也就是说每一个 PushClient 对象里面都封装了拉取的客户端的信息，存储在 PushService 中，方便以后服务变更后推送消息；
>
> 而 PushService 实现了 ApplicationListener 接口，监听 ServiceChangeEvent 服务变更事件，当服务发生变更时，PushService 就会监听到，并会触发回调其监听方法 onApplicationEvent()，
>
> PushClient 保存在 PushService 的 clientMap 中，缓存了当前服务端中所有实例对应的 UDP Client，即保存了所有已订阅服务的可推送目标客户端。
>
> clientMap 它是一个双层 ConcurrentMap：
>
> - 外层 map：key："namespaceId##groupId@@微服务名称"；value：为内层 map
> - 内存 map：key：代表实例的一个字符串，value：该实例对应的 UDP Client，即 PushClient，也就是可推送目标客户端对象。
>
> 整个 map 结构就表示了每一个服务都会对应多个订阅者。

<br>

#### Nacos 服务端监听服务变更

PushService#onApplicationEvent()

PushService 监听 ServiceChangeEvent 事件，当服务发生变更时，回调此方法进行服务变更推送，Server-Client 之间的 UDP 通信是 Nacos Server 向 Nacos Client 发送 UDP 推送请求。

服务端延迟 1s 启动一个定时操作，异步执行服务变更推送操作，异步发送 UDP 广播，完成了以下操作：

1. 从 ServiceChangeEvent 事件中获取到发生了变更的服务，根据名称空间 id 和服务名称，从可推送目标客户端缓存 clientMap 中找到对应的 PushClient，即找到推送目标客户端对象，如果没有找到，可能说明没有客户端发起针对该服务的订阅；
2. 遍历所有订阅了这个服务的 PushClient(推送目标客户端)，跳过那些已经过期了的 PushClient；
3. 将需要发送的数据包装成 AckEntry 对象，而这里需要发送的数据肯定就是这个发生了变更的服务的最新数据了，这些数据由 prepareHostData() 方法提供；
4. 在拿到需要推送给客户端的数据之后，就调用 udpPush() 方法把数据推送给客户端了，通过 udpSocket 原生 API 发送 UDP 数据包给客户端。
5. Nacos 客户端 NacosNamingService 在初始化时就已经做好了接收服务端推送数据的准备了

> prepareHostData() 方法在执行时，会调用 InstanceController#doSrvIpxt() 方法获取指定服务的最新数据，而 doSrvIpxt() 方法会向可推送目标客户端缓存 clientMap 中添加一个 PushClient，但此处不会重复添加 PushClient，因为在添加的时候会判断 PushClient 是否已经存在于 clientMap 中，如果存在，则不会重复添加，而是对这个 PushClient 的 lastRefTime 属性刷新为最新的时间戳，这个 lastRefTime 就能够在上面判断 PushClient 是否过期的时候起到作用；

<br>

#### Nacos 客户端监听服务端推送

NacosNamingService#init()

```java
private void init(Properties properties) throws NacosException {
    ValidatorUtils.checkInitParam(properties);
    this.namespace = InitUtils.initNamespaceForNaming(properties);
    InitUtils.initSerialization();
    initServerAddr(properties);
    InitUtils.initWebRootContext(properties);
    initCacheDir();
    initLogName(properties);

    // 创建 nacos 的 api 请求客户端。
    this.serverProxy = new NamingProxy(this.namespace, this.endpoint, this.serverList, properties);
    this.beatReactor = new BeatReactor(this.serverProxy, initClientBeatThreadCount(properties));
    this.hostReactor = new HostReactor(this.serverProxy, beatReactor, this.cacheDir, isLoadCacheAtStart(properties),
            isPushEmptyProtect(properties), initPollingThreadCount(properties));
}
```

Nacos 客户端 NacosNamingService 在初始化时创建了 HostReactor 组件，在 HostReactor 组件的构造方法中，创建了用于接收服务端推送数据的组件 PushReceiver，这个类会以 UDP 的方式接收服务端推送的服务变更数据。

PushReceiver

```java
/**
 * 这个类会以 UDP 的方式接收来自 Nacos 服务端推送的服务变更数据。
 * @param hostReactor
 */
public PushReceiver(HostReactor hostReactor) {
    try {
        this.hostReactor = hostReactor;
        // 创建 UDP 客户端
        this.udpSocket = new DatagramSocket();
        // 准备一个线程池
        this.executorService = new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r);
                thread.setDaemon(true);
                thread.setName("com.alibaba.nacos.naming.push.receiver");
                return thread;
            }
        });
        // 开启线程任务，准备异步执行接收 nacos 服务端推送的变更数据的任务，即执行 PushReceiver.run()
        this.executorService.execute(this);
    } catch (Exception e) {
        NAMING_LOGGER.error("[NA] init udp socket failed", e);
    }
}

@Override
public void run() {
    // 开启一个无限循环
    while (!closed) {
        try {
            // 创建一个 64k 大小的缓冲区。byte[] is initialized with 0 full filled by default
            byte[] buffer = new byte[UDP_MSS];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

            // 接收 nacos 服务端推送过来的数据。接收来自 Nacos Server 的 UDP 通信推送的数据，并封装到 packet 数据包中
            udpSocket.receive(packet);

            // 数据解码为 JSON 串
            String json = new String(IoUtils.tryDecompress(packet.getData()), UTF_8).trim();
            NAMING_LOGGER.info("received push data: " + json + " from " + packet.getAddress().toString());

            // 将 JSON 反序列化为 PushPacket 对象
            PushPacket pushPacket = JacksonUtils.toObj(json, PushPacket.class);
            String ack;
            // 根据不同的数据类型，生成对应的 ack
            if ("dom".equals(pushPacket.type) || "service".equals(pushPacket.type)) {
                
                // 将来自于 Nacos 服务端推送过来的变更的服务数据更新到当前 Nacos Client 的本地注册表中
                hostReactor.processServiceJson(pushPacket.data);

                ……
            } else if ("dump".equals(pushPacket.type)) {
               ……
            } else {
                ……
            }

            // 向 Nacos Server 发送响应数据，即发送反馈的 UDP 通信
            udpSocket.send(new DatagramPacket(ack.getBytes(UTF_8), ack.getBytes(UTF_8).length,
                    packet.getSocketAddress()));
        } catch (Exception e) {
            if (closed) {
                return;
            }
            NAMING_LOGGER.error("[NA] error while receiving push data", e);
        }
    }
}
```

PushReceiver 会利用线程池来接收推送数据的任务，在接收推送数据的任务中开启了一个无限循环(死循环)，不断地调用 udpSocket 原生 receive() 方法来获取服务端推送来的数据，然后把接收到的数据交给 HostReactor 组件的 processServiceJson() 方法处理，即将来自 Nacos 服务端推送过来的变更的服务数据更新到当前 Nacos 客户端的本地注册表中，更新完本地注册表后，客户端会发布一个 InstancesChangeEvent 事件，发布完成之后，该事件发布者对应的事件订阅者就能够进行监听回调。

**HostReactor#processServiceJson()**

```java
/**
 * 通过 InstancesChangeEvent 事件对应的事件发布者去发布一个 InstanceChangeEvent 事件，
 * 发布完之后，该事件发布者对应的事件订阅者就能够进行监听回调。
 * 事件的发布者和订阅者就是 HostReactor 组件初始化时候创建的
 */
NotifyCenter.publishEvent(new InstancesChangeEvent(serviceInfo.getName(), serviceInfo.getGroupName(),
        serviceInfo.getClusters(), serviceInfo.getHosts()));
```

客户端处理服务器返回的数据。服务端返回的数据交给 processServiceJson() 方法处理，此方法将来自于服务端返回或推送的数据更新到当前客户端本地注册表中。该方法有两种情况会被调用：

1. 客户端调用服务端拉取数据(包含订阅拉取和普通拉取)，服务端返回结果后调用此方法；
2. 服务端监听到服务发生变更事件，推送服务最新的实例结果，会调用此方法。

处理分为两个分支：

1. 本地注册表中没有服务端返回的服务，说明是第一次拉取该服务的实例，则将服务端返回的数据直接保存进本地注册表 serviceInfoMap 中。
2. 本地注册表中存在该服务，则说明客户端之前拉取过该服务，此时需要针对不同条件来更新该服务的实例数据。保存完服务数据之后，整理变更的实例数据，发送实例变更事件，该事件的订阅者就会进行监听回调。其事件的发布是将事件添加进一个事件队列中，这是一个异步操作，由于 DefaultPublisher 在初始化时，会开启一个子线程，这个子线程会不断的(死循环)从队列中获取事件进行处理，即遍历所有的事件订阅者，通知他们处理事件。？？？？？？？？？？？？？？？？？？？？？？？？？？？

Naocs 保证客户端注册表中保存的数据是最新的服务信息数据，是通过服务端在服务发生变更的时候，会推送这个服务的最新数据给到客户端，客户端收到推送数据后，对注册表进行更新。更新完客户端本地注册表之后，会通过 InstancesChangeEvent 事件对应的事件发布者去发布一个 InstanceChangeEvent 事件，即发布一个实例变更的事件，发布完之后，该事件发布者对应的事件订阅者就能够进行监听回调。

HostReactor 组件初始化的时候就注册了一个 InstancesChangeEvent 事件的订阅者，对于注册事件，其实例变更事件订阅者为 InstancesChangeNotifier，未完待补充？？？？？？？

监听器是在 subscribe() 方法中，把指定监听的服务名称和集群名称和对应的监听器放到 listenerMap 中，然后当事件订阅者遍历这些监听器的时候，就会根据发布的事件对象中的服务名称从 listenerMap 中获取到对应的监听器并执行 onEvent() 方法，通过透传的事件对象，用户能够在 onEvent 这个回调方法中获取到指定服务和集群下最新的实例信息了。

---

### Nacos 服务注册与发现流程图总结

<img src="doc/Nacos服务发现原理流程.jpg" alt="nacos-config系统架构" style="zoom:33%;" />

---

## Nacos 的 UDP 通信

### Nacos 客户端与服务端之间的 UDP 通信

Server-Client 之间的 UDP 通信

Nacos Server 向 Nacos Client 发送 UDP 推送请求:

- 服务发生变更，发送变更事件
- 触发 PushService 的监听回调函数 onApplicationEvent()
- 回调函数启动一个定时操作，异步执行向与该变更服务的所有订阅者(Nacos Client)发送 UDP 推送请求：udpSocket.send()，即通知这个发生了变更的服务的所有订阅者

<br>

Nacos Client 接收 Nacos Server 发送的 UDP 请求：

- Nacos Client 的 Tomcat 启动后会触发监听器 AbstractAutoServiceRegistration 调用 onApplicationEvent() 方法，就会注册 NacosNamingService；
- NacosNamingService 在初始化的时候就会创建 HostReactor；
- HostReactor 在创建的时候会创建 PushReceiver；
- **PushReceiver 在创建的时候会异步执行其 run() 方法，这个 run() 方法开启了一个无限循环，用于接收 Nacos Server 发送的 UDP 请求**，
- PushReceiver 处理完请求数据后会向 Nacos Server 发送一个反馈的 UDP 通信请求

> Nacos Client 在应用启动后，会创建 PushReceiver，它会启动一个无限循环，来接收 Nacos Server 端发送的 UDP 通信请求。

<br>

Nacos Server 与 Nacos Client 之间之所以能够保持 UDP 通信，是因为在  Nacos Server 中维护着一个缓存 Map，这个 map 是一个双层 map。外层 Map 的 key 为服务名称，格式为：`namespaceId##groupId@@微服务名`，value 为内存 map；而内层 map 的 key 为 代表 Instance 的字符串，value 为 Nacos Server 与 Nacos Client 进行 UDP 链接的 PushClient，这个 PushClient 是包含了这个 Nacos Client 的 port 等数据。**也就是说  Nacos Server 中维护着每一个注册在其中的 Nacos Client 对应的 UDP 通信客户端 PushClient**。

<br>

Nacos Server 与 Nacos Client 之间的 UDP 通信，发生在什么状况下？

- 当 Nacos Server 通过心跳机制检测到其注册表中维护的 Instance 实例数据发生了变化，其需要将这个变更通知到所有订阅该服务的 Nacos Client客户端，Nacos Server 会发布一个事件，而该事件会触发  Nacos Server 通过 UDP 通信将数据发送给 Nacos Client。

<br>

Nacos Server 与 Nacos Client 之间的 UDP 通信， Nacos Server 充当着 UDP 通信的 Client 端，而 Nacos Client 充当着 Server 端，所以，**在 Nacos Client 中有一个线程处于无限循环中，以随时检测到  Nacos Server 推送来的数据。**

Nacos Server 与 Nacos Client 之间的 UDP 通信，Nacos Server 作为 UDP 通信的 Client 端，其需要知道其链接的 UDP Server，即 Nacos Client 的端口号。在 Nacos Client 定时从  Nacos Server 获取数据时，会随着请求将其 port 发送给  Nacos Server。

<br>

Nacos Server 会在哪种情况下引发其维护的注册表中的 Instance 的健康状态 healthy 发生变更？

- 当 Nacos Server 端定时清除过期 Instance 的任务时检测到某 Instance 超过 15s 未发送心跳时，会将其 healthy 状态由 true 变更为 false；
- 当 Nacos Server 又重新收到 healthy=false 的 Instance 的心跳时，会将其 healthy 的状态由 false 变更为 true。

<br>

### Nacos 服务端之间的通信

**ServiceManager.init()**

Nacos Server 中的 ServiceManager 管理着当前 Server 中的所有服务数据，ServiceManager 实例在创建完毕后，会启三项重要任务:

1. 启动了一个**定时任务**：当前 Nacos Server 每隔 60s 会向其它 Nacos Server 发送一次本机注册表
2. 从其它 Nacos Server 端获取注册表中的所有 Instance 的最新状态并更新到本地注册表
3. 启动了一个**定时任务**，每隔 30s 清理一次注册表中空的 Service。（空 Service 即为没有任何 Instance 的 Service）。但这个清除并不是直接的暴力清除，即并不是在执行定时任务时，一经发现空的 Service 就立即将其清除，而是仅使用一个标记该 Service 为空的计数器加一，当计数器的值超出了设定好的清除阈值（默认为 3）时，才将该 Service 清除。另外这个清除工作并不是直接在本地注册表中清除，而是通过一致性操作来完成的。这样做得好处是不仅清除了本地注册表中的数据，同时清除了其它 Nacos Server 注册表中的数据。

---

## Nacos 健康检查

### Nacos 健康检查机制

Nacos 的健康检测有两种模式：

1. 临时实例
    - 采用客户端心跳检测模式，心跳周期为 5s
    - 心跳间隔超过 15s 则标记为不健康
    - 心跳间隔超 30s 则从服务列表中删除
2. 永久实例
    - 采用服务端主动健康检测的方式
    - 周期为 2000 + 5000 毫秒内的随机数
    - 检测异常只会标记为不健康，并不会删除

**Nacos 为什么会有临时和永久两种实例呢？**

> 以淘宝为例，双十一大促期间，流量会比平常高出很多，此服务肯定需要增加更多实例来应对高并发，而这些实例在双十一之后就无需继续使用了，采用临时实例比较合适。而对于服务的一些常备实例，则使用永久实例更合适。
>
> 与 Eureka 相比，Nacos 与 Eureka 在临时实例上都是基于心跳模式实现的，差别不大，主要是心跳周期不同，Eureka 是 30s，Nacos 是 5s。另外 Naocs 支持永久实例，而 Eureka 不支持。Eureka 只提供了心跳模式的健康检测，而没有主动检测功能。

#### 临时实例健康检查机制

在 Nacos 中，用户可以通过两种方式进行临时实例的注册：

- 通过 Nacos 的 OpenAPI 进行服务注册
- 通过 Naocs 提供的 SDK 进行服务注册

OpenAPI 的注册方式实际是用户根据自身需求调用 HTTP 接口，对服务进行注册，然后通过 HTTP 接口发送心跳到注册中心。在注册服务的同时会注册一个全局的客户端心跳检测任务。在服务端一段时间没有收到来自客户端的心跳后，该任务会将其标记为不健康，如果在间隔的时间内还未收到心跳，那么该任务会将其剔除。

SDK 的注册方式实际是通过 RPC 与注册中心保持链接(此方式是在 Nacos 2.x 版本中使用，旧版本还仍然使用 OpenAPI 的方式)，客户端会定时的通过 RPC 链接向 Nacos 注册中心发送心跳，保持链接的存活。如果客户端和注册中心的链接断开，那么注册中心会主动剔除该客户端所注册的服务，达到下线的效果。同时 Nacos 注册中心还会在启动时，注册一个过期客户端清除的定时任务，用于删除那些健康状态超过一段时间的客户端。

<img src="doc/Nacos 临时实例健康检查.jpg" alt="目录位置" style="zoom: 25%;" />

<br>

#### 持久实例健康检查机制

Nacos 中使用 SDK 对持久实例的注册实际使用的是 OpenAPI 的方式进行注册的，这样可以保证即使是客户端下线后，也不会影响持久实例的健康检查。

对于持久实例的健康检查，Nacos 采用的是注册中心探测机制，注册中心会在持久服务初始化时，根据客户端选择的协议类型注册探活的定时任务。Nacos 现在内置提供了三种探测的协议，即 HTTP、TCP 以及 MySQL。

一般而言，HTTP 和 TCP 已经可以涵盖绝大多数的健康检查场景，MySQL 主要用于特殊的业务场景，例如数据库的主备需要通过服务名对外提供访问，需要确定当前访问数据库是否为主库时，那么此时的健康检查接口，是一个检查数据库是否为主库的 MySQL 命令。

<img src="doc/Nacos 持久实例健康检查.jpg" alt="目录位置" style="zoom: 25%;" />

由于持久服务实例在被主动删除前一直存在的特性，探活的定时任务会不断探测服务的健康状态，并且将无法探测成功的实例标记为不健康。但是有些时候会有这样的场景，有些服务不希望去校验其健康状态，Nacos 也是提供了对应的白名单配置，用户可以将服务配置到该白名单，那么 Nacos 会放弃对其进行健康检查，实例的健康状态始终为用户传入的健康状态。

<br>

### Nacos 客户端发送心跳

#### 触发时机

客户端在注册时，先为临时实例构建了心跳数据，并启动了一个定时任务，定时向服务端发送心跳。

**NacosNamingService#registerInstance**()

```java
// Nacos Client 发起注册请求(包括注册与心跳)
@Override
public void registerInstance(String serviceName, String groupName, Instance instance) throws NacosException {
    // 检测超时参数是否异常，心跳超时时间（默认 15s）必须大于心跳周期（默认 15s）
    NamingUtils.checkInstanceIsLegal(instance);
    // 生成格式：groupName@@serviceId，例如：my_group@@colin-nacos-consumer
    String groupedServiceName = NamingUtils.getGroupedName(serviceName, groupName);
    // 判断当前实例是否为临时实例「默认为临时实例」，临时实例基于心跳的方式做健康检测，永久实例则是由 nacos 主动探测实例的状态
    if (instance.isEphemeral()) {
        // 构建心跳信息数据
        BeatInfo beatInfo = beatReactor.buildBeatInfo(groupedServiceName, instance);
        // 临时实例，向服务端发送心跳请求。「定时任务」。beatReactor 内部维护了一个线程池
        beatReactor.addBeatInfo(groupedServiceName, beatInfo);
    }
    /**
     * 向服务端发送注册请求，最终由 NacosProxy 的 registerService 方法处理
     * 提交一个 POST 的注册请求。url：/nacos/v1/ns/instance
     */
    serverProxy.registerService(groupedServiceName, groupName, instance);
}
```

心跳请求：BeatReactor#addBeatInfo(groupedServiceName, beatInfo)

- nacos 实例分为临时实例与永久实例，临时实例时基于心跳的方式做健康检测，而永久实例则是由 nacos 主动探测实例状态。

- 通过使用一个「one-shot action」一次性定时任务，来发送心跳请求，当 BeatTask 在执行完任务后会再创建一个相同的一次性定时任务，用于发送下一次的心跳请求，这样就实现了一次性定时任务的循环执行。

- **发送心跳的定时任务是由一个新的线程执行的**。

- groupedServiceName 的格式：**my_group@@colin-nacos-consumer**

- beatInfo：心跳信息数据

- 客户端发送心跳的请求路径：/nacos/v1/ns/instance/beat

> Nacos Client 向 Nacos Server 发送的注册、订阅、获取状态等连接请求是通过 NamingService 完成，但是心跳请求不是，心跳是通过 BeatReactor 提交的。而 Nacos Client 向 Nacos Server 发送的所有请求最终都是通过 NamingProxy 完成的提交。

<br>

### Nacos 服务端处理心跳

#### Nacos 服务端处理客户端心跳续约请求

**InstanceController#beat()**

该处理方式主要就是在注册表中查找这个 Instance，若没找到，则创建一个，再注册进注册表中；若找到了，则会更新其最后心跳时间戳。其中比较重要的一项工作是，若这个 Instance 的健康状态发生了变更，其会利用 PushService 发布一个服务变更事件，而 PushService 是一个监听器，会触发 PushService#onApplicationEvent()，就会触发 Nacos Server 向 Nacos Client 发送 UDP 通信，就会触发该服务的订阅者更新该服务。

其实处理心跳请求的核心就是更新心跳实例的最后一次心跳时间，lastBeat，这个会成为判断实例心跳是否过期的关键指标。

<br>

**对于心跳请求到达了服务端，而服务端注册表中却没有找到该 Instance 的情况，有以下两种可能：**

1. 注册请求先提交了，但由于网络原因，该请求到达服务端之前心跳请求却先到达了；
1. 由于网络抖动，客户端正常发送的心跳还没有到达服务端，而服务端就将这个实例 Instance 从注册表中给清除了。网络恢复后，服务端又收到了客户端发来的心跳。

<br>

**服务端对临时实例与持久实例的健康状态的记录又什么不同？**

- 持久实例是通过 **marked** 属性来表示的；fasle：表示实例未被标识，是健康的持久实例，true：表示实例被标识，是不健康的持久实例。
- 临时实例是通过 **healthy** 属性来表示的；true：表示健康的临时实例，false：表示不健康的临时实例。对于临时实例，其 **marked** 属性永远为 fasle。
- 即只要一个实例的 marked 属性为 true，那么这一定是持久实例，且为不健康的持久实例；但仅凭 marked 属性为 false，是无法判断这个实例是否为临时实例，更无法判断其健康状态。

<br>

**Nacos Server 中检测到某个 Instance 实例的心跳超时了 30s，需要将其从注册表中删除，这个删除操作是异步的，通过向自己提交一个删除请求来完成的**，而这个删除请求是通过 Nacos 自研的 HttpClient 提交的，这个 HttpClient 实际是对 Apache 的异步 HttpClient，即 CloseableHttpAsyncClient 进行的封装，即请求最终是通过 CloseableHttpAsyncClient 提交的。

**在 Service 的初始化时开启了清除过期 Instance 实例的定时任务，其”清除“操作与”注销“请求进行了合并。由当前 Nacos Server 向自己提交一个 delete 请求，由 Nacos Server 端的”注销“方法进行处理**

---

## 一致性服务

未完，待补充

Nacos 支持 AP 和 CP 两种⼀致性协议并存：

1. Raft 协议（CP 模式）：是一种强一致性协议算法
2. Distro 协议（AP 模式）：Distro 协议是 Nacos 社区自研的⼀种 AP 分布式协议，是面向临时实例设计的⼀种分布式协议，其保证了在某些 Nacos 节点宕机后，整个临时实例处理系统依旧可以正常工作。作为⼀种有状态的中间件应用的内嵌协议，Distro 保证了各个 Nacos 节点对于海量注册请求的统⼀协调和存储。其数据存储在缓存中，并且会在启动时进行全量数据同步，并定期进行数据校验。

⼀致性协议已经被抽象在了 consistency 的包中，Nacos 对于 AP、CP 的⼀致性协议接口使用抽象都在里面，并且在实现具体的⼀致性协议时，采用了插件可插拔的形式，进⼀步将⼀致性协议具体实现逻辑和服务注册发现、配置管理两个模块达到解耦的目的。consistencyService 是集群一致性接口，将集群一致性委托给具体的实现类：

- 临时实例，委托后采用 nacos 自定义的 Distro 协议实现集群一致性，具体的组件是 DistroConsistencyServiceImpl
- 永久实例，委托后采用简化的 Raft 协议实现集群一致性，具体的组件是 PersistentConsistencyServiceDelegateImpl

---

## Nacos Console 启动

配置单机启动，需配置文件中配置数据库相关参数，并且在启动时添加参数：`-Dnacos.standalone=true`

1. Nacos 单机启动
    - Nacos 默认时集群启动的，因此默认情况下直接启动 Console 模块下的 Nacos 类，会报错而无法启动，若需要单机启动，这需在启动时在 VM options 中配置动态参数：`-Dnacos.standalone=true`，就可以单机启动 Nacos 了。
    - 单机启动的 Nacos，既可以使用内置的 Mysql 数据库，也可以在配置文件中指定使用外置的数据库。
2. Nacos 集群启动
    - 集群模式不允许使用内置 Mysql 数据库，所以若要以集群方式启动 Nacos，首先需要在 console 模块下的配置文件指定数据库的配置，然后在每台主机启动时，配置 VM options 的动态参数：`-Dserver.port=****`，即为集群中的每一台主机都配置其自己的端口。

---

## Nacos Config

### Nacos Config 系统架构

<img src="doc/nacos-config系统架构.jpg" alt="nacos-config系统架构" style="zoom:33%;" />

<br>

### Nacos 数据模型

![nacos数据模型](doc/nacos数据模型.jpg)

Nacos Config 中有一个概念：tenant，其实就是 namespace，是 bootstrap.yml 文件属性 spring.cloud.nacos.config 中指定的 namespace。在代码中为了区分 spring.cloud.nacos.discovery 中指定的 namespace，所以在 Nacos Config 中使用 tenant。

<br>

### Nacos 配置中心总结

#### 几种常见的配置中心的对比

1. 系统架构复杂度：
    - Nacos Config 最为简单，无需消息总线系统，无需 Eureka 等
    - Apollo 和 Spring Cloud Config 系统搭建成本及复杂度较 Nacos Config 要高很多
2. 羊群效应
    - Spring Cloud Config：Config Client 需要提交配置变更请求，当微服务系统很庞大时，任何一个 Config Client 的变更请求的提交，都会引发所有“Bus 在线 Config Client” 的配置更新请求的提交，会引发羊群效应，这会导致 Config Client 的效率下降，导致整个系统的效率下降
    - Nacos Config 和 Apollo 则是“定点更新”，谁的配置变更了向谁推送
3. 自动感知配置变更
    - Spring Cloud Config 是 Config Client 不提交请求，其实无法感知配置变更的。
    - Nacos Config 和 Apollo：当 Config Server 中的配置文件发生变更，Config Client 会自动感知到这个变更，无需 Config Client 端的用户做任何操作
4. 配置文件类型
    - Nacos Config 和 Spring Cloud Config 的配置文件支持比较多的类型，包括 yml、text、json、xml、html、properties 等
    - Apollo 只支持 xml、text、properties，不支持 yml

<br>

#### 配置文件的加载

Nacos Config Client 要加载的配置文件有三种

1. 自身配置

   ```yml
   spring:
       application:
           name: colin-nacos-config-source
       cloud:
           nacos:
               config:
                   server-addr: localhost:8848
                   # 指定配置文件类型为 yml
                   file-extension: yml
       # 多环境选择
       profiles:
           active: test
   ```



2. 共享配置：要求共享配置文件与当前应用配置文件必须在同一个 Group 中

   ```yml
   spring:
       application:
           name: colin-nacos-config-source
       cloud:
           nacos:
               config:
                   server-addr: localhost:8848
                   # 指定配置文件类型为 yml
                   file-extension: yml
                   # 共享配置：要求共享配置文件与当前应用配置文件必须在同一个 Group 中
                   # 共享配置(方式一)
                   # shared-configs: redis.yml,mysql.yml
                   # 共享配置(方式二)
                   shared-configs[0]:
                       data-id: redis.yml
                       refresh: true
                   shared-configs[1]:
                       data-id: mysql.yml
                       refresh: true
   
       # 多环境选择
       profiles:
           active: test
   ```



3. 扩展配置：扩展配置文件与当前应用配置文件无需在同一个 Group 中

   ```yml
   spring:
       application:
           name: colin-nacos-config-source
       cloud:
           nacos:
               config:
                   server-addr: localhost:8848
                   # 指定配置文件类型为 yml
                   file-extension: yml
                   # 扩展配置：扩展配置文件与当前应用配置文件无需在同一个 Group 中
                   extension-configs[0]:
                       data-id: redis.yml
                       refresh: true
                       group: other
                   extension-configs[1]:
                       data-id: mysql.yml
                       refresh: true
                       group: other
       # 多环境选择
       profiles:
           active: test
   ```

<br>

配置文件加载顺序的说明：

- 以上三类配置文件的加载顺序为：共享配置 → 扩展配置 → 当前应用配置。如果存在相同属性配置了不同的值，则后加载的会将先加载的给覆盖掉。即优先级为：共享配置 < 扩展配置 < 应用自身配置。
- 对于配置文件的加载过程，又存在三种可用选择：
    1. 应用本地存在同名配置
    2. 远程配置中心存在同名配置
    3. 本地磁盘快照 snapshot 中存在同名配置。
    4. 以上三种同名配置的优先级为：本地配置 > 远程配置 > 快照配置；只要前面加载到了，后面的就不再加载
- 若要在应用本地存放同名配置，则需要存放到当前用户主目录下的 `/Users/colin/nacos/config/fixed-localhost_8848_nacos/data/config-data/{groupId}` 目录中
- 若开启了配置的快照功能，则默认会将快照记录在当前用户主目录下的 `/Users/colin/nacos/config/fixed-localhost_8848_nacos/snapshot/{groupId}` 目录中

<br>

#### 配置文件的加载时机

- SpringBoot 在启动时，会准备环境，就会调用 **NacosPropertySourceLocator.locate()** 方法，此方法会从配置中心加载配置文件。按顺序分别加载共享配置、扩展配置、应用自身配置。
- 加载自身配置时，会分为以下三种情况：
  1. 加载仅有文件名称，没有扩展名的配置文件
  2. 加载有文件名称，也有扩展名的配置文件
  3. 加载有文件名称、有扩展名、并且还包含多环境选择 profile 的配置文件
- 而加载每种配置时，根据配置文件所在位置，按照顺序依次加载：优先加载本地配置；若没有本地配置，则加载远程配置中心中的配置；若本地和远程都没有，则加载快照中的配置文件。

<br>

#### 配置文件的动态更新

一般情况下 Server 端的数据变更，若要 Client 端感知到，可以选择两种模型：

- Push 模型：当 Server 端的数据发生了变更，其会主动将更新推送给 Client 端。Push 模型适合于 Client 数量不多，且 Server 端数据变更比较频繁的场景。其实时性比较好，但其需要维护**长链接**，占用系统资源。
- Pull 模型：需要 Client 定时查看 Server 端数据是否发生了变更。其实时性不好，切可能会产生数据更新的丢失。

<br>

**长轮询模型：**

- 长轮询模型整合了 Push 模型 和 Pull 模型的优势。Client 端定时发起 Pull 请求，查看 Server 端数据是否发生了变更，若发生了变更，则 Server 端立即将变更数据以响应的形式发送给 Client 端；若没有发生变更，Server 端不会发送任何信息，但其会临时性的保持住这个链接一段时间，若在此期间，Server 端的数据发生了变更，这个变更就会触发 Server 端向 Client 端发送变更结果，这次的执行，就是因为长链接的存在；若在此期间，没有发生变更，则 Server 端将放弃这个长链接，等待下一次 Client 端的 Pull 请求。
- 长轮询模型，是 Push 模型与 Pull 模型的整合，既减少了 Push 模型中长链接的维护的时间，又缓解了 Pull 模型实时性较差的问题。

<br>

#### client 端定时发出“配置文件变更”检测

应用启动时，会创建 NacosConfigAutoConfiguration，NacosConfigAutoConfiguration 会创建 NacosConfigManager，NacosConfigManager 会创建 NacosConfigService，**NacosConfigService 会创建 ClientWorker，ClientWorker 会启动一个定时任务，来周期性的向 nacos  config server 端发出“配置文件变更”检测请求**。

nacos config client 中是以异步线程池的方式向 nacos config server 端发出长轮询任务请求，为了保证执行效率，执行这个异步请求的线程池的核心线程数是当前主机处理器可用的逻辑内核数量，这样可用保证一个逻辑内核处理一个线程。

nacos config client 向 nacos config server 发出的长轮询任务的链接请求数量是与动态变更的配置文件的数量有关的。默认情况下，每 3000 个配置文件会发出一个长链接请求，nacos config server 端会对这个长链接中的 3000 个配置进行轮询检测其是否发生了变更。

<br>

#### client 端将配置变更同步到应用实例中

1. nacos config client 的每个配置文件对应的 CacheData 是什么时候创建的？
    - **一旦  nacos config client 应用启动完毕，就会遍历所有配置文件，为每个配置文件创建一个本地缓存 CacheData，并为每个 CacheData 添加一个监听器。一旦监听到 CacheData 中的数据发生了变更，就会引发监听回调函数的执行。该回调函数并未直接从 CacheData 中读取变更数据，而是发布了一个刷新事件 RefreshEvent，该事件能够触发所有被 @RefreshScope 标注的类的实例被重新创建并初始化，而初始化时使用的会自动更新的属性（被 @Value 标注的属性）值就来自于 CacheData。**
    - @RefreshScope的理解：在 Spring 容器中，对于 Bean 是分区域进行管理的，每一个 scope 就是一个区域。例如：singleton、prototype 等。在 Spring Cloud 中又新增了一个自动刷新的 scope 区域，refresh。对于可刷新 Bean 的管理，Spring Cloud 首先是将 Spring 容器中 refresh 区域的所有 Bean 全部清除掉，然后在使用这些 Bean 时重新创建并初始化这些 Bean，而初始化时使用到的数据就是来自最新的数据，即从 CacheData 中取值。

<br>

2. 从  nacos config client 的角度看，其是如何做到配置文件自动更新的？
    - 在  nacos config client 启动时就会创建一个 NacosConfigService 实例，用于处理 NacosConfig 相关的操作。但实际上这些操作都是由 ClientWorker 实例在完成，在创建 NacosConfigService 实例时就会创建一个 ClientWorker 实例
    - **在创建 ClientWorker 实例时，其会启动一个周期性执行的定时任务：从 nacos config server 中获取到发生了变更的配置数据，并将这些数据更到本地缓存 cacheMap 中。nacos config client 中标注了 @RefreshScope 注解的实例获取到的更新数据就是取的 cacheMap 的 value 值。**
    - cacheMap 是一个很重要得缓存 map 集合，cacheMap 的 key 为配置文件的 key，即 dataId+groupId，value 为存放着当前  nacos config client 中所需要的每个配置文件对应的本地缓存 CacheData。在  nacos config client 接收到来自于 nacos config server 端的变更数据后，会将这个变更数据更新到其对应的本地 CacheData 中。nacos config client 中标注了 @RefreshScope 注解的实例获取的更新数据就是从 CacheData 中获取的。

<br>

#### server 端处理 client 的配置变更检测请求

**ConfigController.listener() → ConfigServletInner.doPollingConfig()**

**总体思路**：

当 nacos config server 接收到 nacos config client 发送过来的配置变更检测请求后，首先会解析出请求指定的要检测的所有目标配置文件，同时也会解析出 nacos config client 对处理方式的要求。server 的处理方式有四种类型：

1. **短轮询**：**没有长连接维护**。nacos config server 接收到 nacos config client 发送的请求后，立即轮询检测所有目标配置文件是否发生了变更，并将检测结果立即返回给 nacos config client。不过这个返回的结果与 Nacos Client 的版本有着密切的关系，版本不同形成的结果也就不同。
2. **固定时长的长轮询**：nacos config server 接收到 nacos config client 发送的请求后，会直接维护一个指定的固定时长的长连接，默认是 30s。长连接结束前会检测一次是否发生了变更。不过，在长连接维护期间是不检查变更情况的。
3. **不挂起的非固定时长的长轮询**：与短轮询类似。nacos config server 接收到 nacos config client 发送的请求后，立即轮询检测所有目标配置文件是否发生了变更，并将检测结果立即返回给 nacos config client。与短轮询不同的是，其返回的结果与 Nacos Client 的版本无关。
4. **挂起的非固定时长的长轮询**：nacos config server 接收到 nacos config client 发送的请求后，会先检测是否发生了配置变更。若发生了，则将结果直接返回给  nacos config client，并关闭链接。若未发生配置变更，则首先会将这个长轮询实例写入到一个缓存队列 allSubs 中，然后维护一个 30s 的长连接（这个时长用户不能自定义），时间结束，长连接直接关闭。在长连接维护期间，系统同时监听着配置变更事件，一旦发生变更，就会立即将变更发送给相应的长轮询对应的  nacos config client，并关闭连接。

<br>

nacos config server 接收到 nacos config client 发送的请求后，首先从请求中获取到 nacos config client 所要检测的配置文件的 key，然后对这些配置文件立即进行配置变更检测。如果有配置文件发生了变更，则立即将这些发生了变更的配置文件 key 以 response 的方式发送给 nacos config client 。若没有检测到变更，则为这个发送请求的 nacos config client 创建一个长轮询客户端实例，在这些客户端实例中定义并异步执行了一个 29.5s 的定时任务。该任务再是次查找发生了变更的配置文件的 key，并将这些 key 以response 的方式发送给 nacos config client。

<br>

nacos config server 是如何判断配置文件是否发生了变更？

- 使用来自于 nacos config client 端的配置文件的 md5 与 nacos config server 端的配置文件的 md5 进行对比：若相等，则表示未发生变更；若不相等，则表示发生了变更。

<br>

对于 nacos config client 所提交的每个“配置变更检测请求”，nacos config server 都会为其创建一个长轮询客户端，而每一个长轮询客户端，最多可以检测 3000 个配置文件的变更情况。即这个长轮询客户端是为每次发送的“配置变更检测请求”而创建的。

<br>

#### server 端在非固定时长的长轮询期间对配置变更的感知

总体思路：

在 nacos config server  启动时会创建 LongPollingService 实例，该实例用于处理长轮询相关的操作，LongPollingService 在创建时会首先创建一个 allSubs 队列，同时还会注册一个 LocalDataChangeEvent 的订阅者。一旦 nacos config server 中保存的配置发生了变更，就会触发订阅者的回调函数的执行，而回调函数则会引发 DataChangeTask 的异步任务的执行。

DataChangeTask 任务就是从当前 nacos config server 所持有的所有长轮询实例集合 allSubs 队列中查找，到底是那个长轮询实例的这个配置文件发生了变更，然后将这个变更的配置文件的 key 发送给这个长轮询实例对应的 nacos config client，并将这个长轮询实例从 allSubs 队列中删除。
