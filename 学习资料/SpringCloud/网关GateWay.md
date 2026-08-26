* 前端所有请求的流量入口
* 满足predicates规则->转给对应的路由->HandlerMapping->WebHandler->filter
1.Route
## Predicate
* 一个路由写了多个断言，那么所有都满足才会继续往下传
* 有特别多种断言的工厂，每一种有不同的参数代表不同的断言判断
name:断言的名字
args:
    - 
    -
    -

3.Filter