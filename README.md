WebSocketHandler 调用方法文档
概述
WebSocketHandler 是一个基于 Jakarta WebSocket 的服务器端点类，支持单用户多连接、用户分组管理、消息广播等功能，并与 Redis 集成实现用户状态管理。

<dependency>
    <groupId>io.github.mandala5741</groupId>
    <artifactId>ylc-websocket-spring-boot-starter</artifactId>
    <version>1.0.2</version>
</dependency>

连接管理
1. 建立 WebSocket 连接
   URL: /websocket/{username}

参数说明:

username: 用户标识，格式为 部门,组ID,用户ID（例如：dept1,500229000000,user123）

示例:

javascript
// JavaScript 前端示例
const socket = new WebSocket('ws://localhost:8080/websocket/dept1,500229000000,user123');
注意:

用户名格式必须符合 部门,组ID,用户ID 格式，否则连接会被拒绝

特殊用户名 "server" 除外

消息发送方法
2. 向指定用户发送消息
   java
   // 静态方法，可在任意位置调用
   WebSocketHandler.sendMessageToUser("dept1,500229000000,user123", "Hello!");
   参数说明:

username: 目标用户的完整标识符

message: 要发送的消息内容

效果:

如果目标用户在线，消息会发送给该用户的所有连接实例

如果用户不在线，会打印提示信息

3. 向指定分组发送消息
   java
   // 在 WebSocketHandler 实例中调用
   websocketHandler.sendMessageToGroup("500229000000", "Group message");
   参数说明:

targetGroupId: 目标组ID

message: 要发送的消息内容

效果:

向指定组ID的所有在线用户发送消息

4. 向所有用户广播消息
   java
   // 在 WebSocketHandler 实例中调用
   websocketHandler.sendMessageAll("Broadcast message");
   消息接收与处理
5. 消息格式要求
   客户端发送的消息应为 JSON 格式，包含以下字段：

json
{
"type": "消息类型",
"to": "接收对象",
"status": "状态码",
"message": "具体内容",
"username": "发送者"
}
6. 支持的消息类型
   类型 "999" - 心跳检测
   json
   {
   "type": "999",
   "status": "状态码"
   }
   服务器会回复 pong 消息保持连接。

类型 "All" - 广播消息
向所有在线用户发送消息。

类型 "1" - 分组内广播（排除自己）
json
{
"type": "1",
"username": "发送者标识",
"to": "接收对象",
"message": "内容"
}
向同一分组的其他用户发送消息。

类型 "2" - 点对点私聊
json
{
"type": "2",
"to": "目标用户标识",
"message": "私聊内容"
}
Redis 用户管理方法
7. 存储用户信息
   java
   // 存储单个用户（自动调用）
   storeUser("dept1,500229000000,user123");
8. 批量存储用户
   java
   List<String> users = Arrays.asList(
   "dept1,500229000000,user123",
   "dept1,500229000000,user456"
   );
   batchStoreUsers(users);
9. 续期用户
   java
   renewUser("dept1,500229000000,user123");
   重置用户的过期时间为 24 小时。

10. 移除用户
    java
    removeUser("dept1,500229000000,user123");
    从 Redis 中删除用户信息。

11. 检查用户活跃状态
    java
    boolean isActive = isUserActive("500229000000", "user123");
12. 获取用户剩余时间
    java
    Long ttl = getUserTTL("500229000000", "user123");
    返回剩余秒数。

13. 清理过期用户
    java
    cleanupExpiredUsers("500229000000");
    手动清理指定分组中已过期的用户。

14. 获取分组用户列表
    java
    List<String> userIds = getUserIdsFromRedisByGroupId("500229000000");
    工具方法
15. 提取组ID
    java
    String groupId = extractGroupId("dept1,500229000000,user123", 1);
    String userId = extractGroupId("dept1,500229000000,user123", 2);
16. 提取部门信息
    java
    String department = getDepartmentFromCompositeId("dept1,500229000000,user123");
    连接状态监控
17. 获取在线人数
    java
    int onlineCount = WebSocketHandler.getOnlineCount();
18. 获取在线用户列表
    java
    Set<String> onlineUsers = WebSocketHandler.webSocketMap.keySet();
    使用示例
    示例 1：完整连接和消息流程
    java
    // 1. 客户端建立连接
    // WebSocket URL: ws://localhost:8080/websocket/dept1,500229000000,user123

// 2. 服务器端存储用户到 Redis
storeUser("dept1,500229000000,user123");

// 3. 发送消息到指定用户
WebSocketHandler.sendMessageToUser("dept1,500229000000,user123", "Welcome!");

// 4. 客户端发送分组消息
// JSON: {"type": "1", "username": "dept1,500229000000,user123", "message": "Hi all!"}
示例 2：定时清理过期用户
java
// 可以作为定时任务执行
@Scheduled(fixedDelay = 3600000) // 每小时执行一次
public void cleanupTask() {
// 清理所有分组中的过期用户
Set<String> groupIds = getGroupIdsFromRedis(); // 需要自己实现获取所有组ID的方法
for (String groupId : groupIds) {
cleanupExpiredUsers(groupId);
}
}
注意事项
线程安全:

所有静态变量和方法都使用 synchronized 确保线程安全

使用 ConcurrentHashMap 和 CopyOnWriteArraySet 存储连接信息

连接管理:

支持单用户多连接

自动心跳检测（30秒无心跳自动断开）

连接超时机制（30分钟自动断开）

Redis 集成:

每个用户独立 24 小时过期时间

支持用户续期

需要配置 RedisTemplate Bean

错误处理:

连接异常会自动关闭并清理资源

消息发送异常有基本的错误处理

性能考虑:

使用异步消息发送

批量操作减少 Redis 调用次数

定时清理避免内存泄漏

依赖要求
Jakarta WebSocket API

Spring Framework

Hutool JSON 工具

Redis (用于用户状态管理)

Lombok (日志支持)

此文档涵盖了 WebSocketHandler 类的主要公共方法，按照这个文档可以正确调用和使用 WebSocket 功能。