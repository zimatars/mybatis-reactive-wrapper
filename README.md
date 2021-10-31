# MyBatis Reactive Wrapper
### MyBatis 响应式 Wrapper (based on JDBC)

* 通过切换线程池避免 JDBC 调用对web层造成阻塞，完整支持 MyBatis 原有功能
* 适配 org.springframework.transactionReactiveTransactionManager，支持 Spring 事务

附：[R2DBC版MyBatis](https://github.com/zimatars/mybatis-reactive)

### 快速开始
### 下载源码编译
```sh
    git clone https://github.com/zimatars/a-mybatis-reactive-wrapper.git
    cd a-mybatis-reactive-wrapper/
    mvn -Dmaven.test.skip=true clean install
```

### 引入依赖（自行install）
已包含mybatis-spring-boot-starter，勿重复引入
```xml
    <dependency>
        <groupId>com.zimatars</groupId>
        <artifactId>a-mybatis-reactive-wrapper</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </dependency>
```
### 1.MyBatis线程池配置
```yml
mybatis:
  scheduler:
    name: mybatis-thread
    thread-cap: 20
    queued-task-cap: 100
```
### 2.mapper声明
```java
@Mapper
public interface UserMapper {
    
    Mono<User> getById(Long id);

    Flux<User> selectList();

    Mono<Integer> add(User user);
}
```
### 3.spring 事务

```java
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
  ....
    
  @Transactional
  public Mono<Integer> add() {
    return userMapper.add();
  }
}
```

