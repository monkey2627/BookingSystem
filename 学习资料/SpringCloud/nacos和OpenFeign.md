* 用于作为注册中心和配置中心
# nacos使用之注册中心
* 3.0强行要求用mysql作为数据库没有内置数据库了，并且要加上鉴权，nacos在数据库中维护自己的表
* 引入nacos的jar包，在各个微服务的配置文件中配置nacos的地址就可以在微服务启动后自动注册了
## @EnableDiscoveryClient
此注解放在app的入口类上用来开启服务发现的功能,指的是当前服务去发现别的微服务的实例列表
## DiscoveryClient对象,是spring提供的规范，无论用那个注册中心都可以调用
* getServices()
* NacosServiceDiscovery，是引入nacos才能调用的，和spring的用法基本相同
DiscoveryClient 和 OpenFeign 是两种不同的跨服务调用方式，教程用的是更底层的写法：

  // DiscoveryClient 方式（底层，手动）
  @Autowired
  private DiscoveryClient discoveryClient;

  public Product getProductFromRemote(Long id) {
      // 1. 手动从 Nacos 查服务实例列表
      List<ServiceInstance> instances = discoveryClient.getInstances("product-service");
      // 2. 手动取一个实例（自己实现负载均衡）
      ServiceInstance instance = instances.get(0);
      // 3. 拼 URL
      String url = "http://" + instance.getHost() + ":" + instance.getPort() + "/product/" + id;
      // 4. 手动给远端发 HTTP 请求，远端返回的是实际是一个json，product.class表示将这个json解析为一个product对象
      return restTemplate.getForObject(url, Product.class);
  }

  // OpenFeign 方式（高层，声明式）
  @FeignClient(name = "product-service")
  public interface ProductFeignClient {
      @GetMapping("/product/{id}")
      Product getById(@PathVariable Long id);
  }

  // 调用时像本地方法一样
  Product p = productFeignClient.getById(id);

  两者本质一样，都是从 Nacos 拿地址再发 HTTP，区别是：

  ┌──────────┬─────────────────┬──────────────┐
  │          │ DiscoveryClient │  OpenFeign   │
  ├──────────┼─────────────────┼──────────────┤
  │ 代码量   │ 多，手动拼 URL  │ 少，只写接口 │
  ├──────────┼─────────────────┼──────────────┤
  │ 负载均衡 │ 自己实现        │ 自动         │
  ├──────────┼─────────────────┼──────────────┤
  │ 教程用途 │ 演示原理        │ 实际开发用   │
  └──────────┴─────────────────┴──────────────┘

  教程让你用 DiscoveryClient 是为了让你看清楚底层发生了什么，实际项目都用 Feign。
## 用loadbalancerClient实现负载均衡
调用Choose()方法自动
* 第二种方法，不用显示声明一个loadbalancerClient再调用choose方法，
可以直接在restTemplate上放一个@LoadBalanced注解，此时将ip用service-product(即要调用的微服务名称写在url中)
restTemplate在发送请求时会自动选择合适的ip
## 实例缓存
注册中心即使宕机也能正确调用
# nacos使用之配置中心
* 动态刷新！即在nacos中改了程序中配置也一样改了
* 配置监听：configservice的addlistener(要监听的dataid，组，new listener（）{

    重写线程池方法和接受配置信息方法
})
* 优先级配置中心>微服务内部
* 名称空间区分环境，组区分多个微服务，id区分多种类型的配置
* 如何能实现按照需求加载，如何指定加载的命名空间和分组？进阶还可以用spring自己本来的activate-on-profile来指定
以及用---来分割不同配置，profiles-activa
spring.config.namespace，spring.config.import
# OpenFeign 远程调用（通过注册中心/直接用api）aram
* 一个声明式的rest客户端，可以用来发送远程调用（http请求，说明朝谁发请求，什么路径什么返回值
* 注解驱动，部分注解复用spring mvc但逻辑相反
* 建立连接>发送请求>返回数据
## @EnableFeignClient：开启远程调用功能，这个远程调用是自动负载均衡的
@FeignClient(value=想调用的微服务的名字)：表明这个接口是一个feign客户端，通过注册中心调用微服务的时候value必须写注册中心里注册的名字
@FeignClient(value=随便起个名字，url=""):表明这个接口是一个feign客户端，调用一个
@GetMapping：同springmvc中的注解名字一样，表示get请求map，标注在controller上是接收，标注在feign客户端是发送请求
@PathVaribale("名字")：表明这是一个路径变量，拼在请求里
@RequstHeader("名字"):表示放入一个请求头
@RequestParam("名字"):表示将其放在请求参数上
## 配置
* 可以用配置来开启日志
* 超时控制：给远程调用加一个等待时间如果超过时间的话就中断调用，返回错误信息或者兜底数据(兜底数据结合熔断框架)
1. connectTimeout:建立连接太久
2. readTimeout:返回时间太久
* 重试机制
## 拦截器
请求拦截器和响应拦截器
* 请求拦截器，在请求之前调用
* 容器中只要有RequestInceptor，就会被openfeign调用，所以
如果我们自己想实现一个拦截器就继承自RequestInceptor重写其apply方法就好
## 整合sentinel实现兜底返回
@FeignClient(...,fallback=兜底类的类名.class):表名应用一个兜底的类，这个类需要继承feign客户端类
