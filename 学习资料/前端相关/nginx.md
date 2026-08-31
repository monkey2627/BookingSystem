# nginx基本概念
* http和反向代理的web服务器
* 反向代理：1.正向代理2.反向代理
* 负载均衡
* 动静分离
# nginx使用常用命令
* 查看版本号
* 启动
* 关闭
* 重新加载
## 配置文件
* 配置文件的位置
* 全局块：配置nginx服务器整体运行的一些指令

* events块：影响nginx服务器与用户的网络连接


* http块：包含http全局块和serve块
## 通过nginx反向代理访问过程的分析
## 配置过程
1.windows host文件中配置域名和ip的对应关系
ip 域名
2.找到nginx的配置文件，在server{}中配置转发逻辑，proxy_pass 要转发到的ip:端口 （即运行后端服务的ip和端口）
>每一条转发逻辑的配置：location 访问路径 {proxxy_pass ip:端口} ，可以配置多个location
### 负载均衡配置
在http块里 配置 upstream myserver{
    server ip:port；
    ...
    ...
}
{proxxy_pass myserver:端口} 
* 分配服务器策略：
1.默认轮询，每个请求按照时间顺序分配
2.weight，权重越高被分配客户端越多
3.IP has 根据ip决定，一个ip固定访问一个服务器
### 