package com.cqcloud.platform.handler;

import cn.hutool.extra.spring.SpringUtil;
import cn.hutool.json.JSONUtil;
import io.micrometer.common.util.StringUtils;
import jakarta.websocket.*;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * @author weimeilayer@gmail.com ✨
 * @date 💓💕 2024年4月12日 🐬🐇 💓💕
 */
@Slf4j
@Component
@ServerEndpoint("/websocket/{username}")
public class WebSocketHandler {

	public static synchronized int getOnlineCount() {
		return onlineCount;
	}

	public static synchronized void addOnlineCount() {
		WebSocketHandler.onlineCount++;
	}

	public static synchronized void subOnlineCount() {
		WebSocketHandler.onlineCount--;
	}

	// 静态变量，用来记录当前在线连接数。应该把它设计成线程安全的。
	private static int onlineCount = 0;

	// 根据名字存储websocket对象CopyOnWriteArraySet线程安全set，ConcurrentHashMap线程安全map
	public static Map<String, CopyOnWriteArraySet<WebSocketHandler>> webSocketMap = new ConcurrentHashMap<>();

	// 与某个客户端的连接会话，需要通过它来给客户端发送数据
	public Session session;

	// 心跳时间,长时间没心跳踢掉连接
	public long heartBeatTime;

	// 初次连接时间，用于控制连接时间过长，踢掉连接
	public long beginTime;

	/**
	 * 用户名称
	 */
	public String username;

	private static final String GROUP_USER_HASH = "group:users:";

	private static final String USER_EXPIRE_SET = "group:expire:";

	/**
	 * 发送消息
	 * @param username
	 * @param message
	 */
	public static void sendMessageToUser(String username, String message) {
		// 检查用户名是否在 map 中存在
		if (webSocketMap.containsKey(username)) {
			// 获取该用户的 WebSocketHandler 集合
			CopyOnWriteArraySet<WebSocketHandler> userHandlers = webSocketMap.get(username);

			// 遍历该用户的所有连接（每个用户可能有多个 WebSocket 连接）
			for (WebSocketHandler handler : userHandlers) {
				// 通过 WebSocketHandler 实例发送消息
				handler.sendMessageOne(message, username);
			}
		}
		else {
			System.out.println("并无在线用户: " + username);
		}
	}

	/**
	 * 连接建立成功调用的方法
	 */
	@OnOpen
	public void onOpen(@PathParam("username") String username, Session session) {
		this.username = username;
		this.session = session;
		this.heartBeatTime = System.currentTimeMillis();
		this.beginTime = System.currentTimeMillis();
		// 登陆用户必须按照用户id 格式登陆
		if (!"server".equals(username) && username.split(",").length < 3) {
			return;
		}
		// 存储用户
		storeUser(username);
		// 将用户添加到websocket，支持单用户多出链接
		if (webSocketMap.containsKey(username)) {
			webSocketMap.get(username).add(this);
		}
		else {
			CopyOnWriteArraySet websocketSet = new CopyOnWriteArraySet();
			websocketSet.add(this);
			webSocketMap.put(username, websocketSet);
			addOnlineCount(); // 在线数加1
		}
		// 注释掉 会退出
		Map<String, Object> messageMap = new ConcurrentHashMap<>();
		messageMap.put("type", "0");
		messageMap.put("message", username + "加入8000端口的的当前在线人数为" + getOnlineCount());
		messageMap.put("to", "all");
		messageMap.put("status", "0");
		messageMap.put("users", webSocketMap.keySet());
		messageMap.put("username", "server");
		sendMessageAll(JSONUtil.toJsonStr(messageMap));
	}

	/**
	 * 发送消息给所有用户
	 * @param message
	 * @throws IOException
	 */
	public void sendMessageAll(String message) {
		for (String key : webSocketMap.keySet()) {
			for (WebSocketHandler websocket : webSocketMap.get(key)) {
				websocket.session.getAsyncRemote().sendText(message);
			}
		}
	}

	/**
	 * 发送消息给同一组的所有用户
	 * @param targetGroupId 目标组ID
	 * @param message 消息内容
	 */
	public void sendMessageToGroup(String targetGroupId, String message) {
		for (String key : webSocketMap.keySet()) {
			// 解析key，获取组ID
			String[] keyParts = key.split(",");
			if (keyParts.length >= 2 && keyParts[1].equals(targetGroupId)) {
				for (WebSocketHandler websocket : webSocketMap.get(key)) {
					try {
						websocket.session.getAsyncRemote().sendText(message);
					}
					catch (Exception e) {
						// 处理发送异常，可以记录日志或移除失效的连接
						e.printStackTrace();
					}
				}
			}
		}
	}

	/**
	 * 连接关闭调用的方法
	 */
	@OnClose
	public void onClose() {
		if (StringUtils.isNotEmpty(this.username)) {
			try {
				if (this.session.isOpen()) {
					this.session.close();// 强制关闭
				}
				webSocketMap.get(username).remove(this);// 删除链接
				if (webSocketMap.get(username).isEmpty()) {
					webSocketMap.remove(username);
					// 删除redis用户
					removeUser(username);
					subOnlineCount(); // 在线数减1
					// 刷新用户列表
					Map<String, Object> messageMap = new ConcurrentHashMap<>();
					messageMap.put("type", 0);
					messageMap.put("status", "0");
					messageMap.put("message", username + "退出！当前在线人数为" + getOnlineCount());
					messageMap.put("users", webSocketMap.keySet());
					sendMessageAll(JSONUtil.toJsonStr(messageMap));
				}
			}
			catch (Exception e) {
				System.err.println("关闭连接出错 : " + e.getLocalizedMessage());
			}
		}
	}

	/**
	 * 收到客户端消息后调用的方法
	 * @param message 客户端发送过来的消息
	 */
	@OnMessage
	public void onMessage(String message) {
		// 刷新心跳时间
		this.heartBeatTime = System.currentTimeMillis();
		// 群发消息
		cn.hutool.json.JSONObject messageJson = JSONUtil.parseObj(message);
		Object type = messageJson.get("type");// 消息类型
		Object toUser = messageJson.get("to");// 接收对象
		Object status = messageJson.get("status");// 接收对象
		// 心跳检测
		if ("999".equals(type)) {
			Map<String, Object> messageMap = new ConcurrentHashMap<>();
			messageMap.put("type", "1");
			messageMap.put("message", "pong");
			messageMap.put("username", "服务器");
			messageMap.put("to", this.username);
			messageMap.put("status", status);
			sendMessageOne(JSONUtil.toJsonStr(messageMap), this.username);
			return;
		}
		// 发送消息
		if ("All".equalsIgnoreCase(type + "")) {
			sendMessageAll(message);
		}
		if ("1".equalsIgnoreCase(type + "")) {
			String groupId = extractGroupId(messageJson.get("username").toString(), 1);
			String currentUserId = extractGroupId(messageJson.get("username").toString(), 2);

			List<String> userIds = getUserIdsFromRedisByGroupId(groupId);
			// 使用Stream过滤掉当前用户ID
			List<String> otherUserIds = userIds.stream()
				.filter(userId -> !userId.equals(currentUserId))
				.collect(Collectors.toList());
			// 群发同一个区划的给其他用户
			for (String userId : otherUserIds) {
				String compositeId = getDepartmentFromCompositeId(toUser.toString()) + "," + groupId + "," + userId;
				System.out.println("群发给用户：" + compositeId);
				sendMessageAll(message, compositeId);
			}
		}
		if ("2".equalsIgnoreCase(type + "")) {
			sendMessageOne(message, toUser + "");
		}
	}

	/**
	 * 从复合ID中提取部门
	 */
	public static String getDepartmentFromCompositeId(String compositeId) {
		if (compositeId == null) {
			return null;
		}
		String[] parts = compositeId.split(",");
		return parts.length >= 1 ? parts[0] : null;
	}

	/**
	 * 发生错误时调用
	 */
	@OnError
	public void onError(Throwable error) {
		error.printStackTrace();
	}

	/**
	 * 发送消息
	 * @param message
	 * @throws IOException
	 */
	public void sendMessage(String message) {
		// this.session.getBasicRemote().sendText(message);//同步
		this.session.getAsyncRemote().sendText(message);// 异步
	}

	/**
	 * 发送消息给指定用户
	 * @param message
	 * @param toUserName
	 */
	public void sendMessageAll(String message, String toUserName) {
		webSocketMap.keySet().forEach(e -> {
			if (e.equals(toUserName)) {
				webSocketMap.get(e).forEach(f -> {
					try {
						f.session.getAsyncRemote().sendText(message);
						CompletableFuture.runAsync(() -> {
							try {
								// 记录结果
							}
							catch (Exception e1) {
								// 处理异常
								log.error("收到结果记录插入失败", e);
							}
						});
					}
					catch (Exception e2) {
						f.session.getAsyncRemote().sendText(message);
					}
				});
			}
		});
	}

	/**
	 * 发送消息给指定用户
	 * @param message
	 * @param toUserName
	 */
	public void sendMessageOne(String message, String toUserName) {
		webSocketMap.keySet().forEach(e -> {
			if (e.equals(toUserName)) {
				webSocketMap.get(e).forEach(f -> {
					try {
						f.session.getAsyncRemote().sendText(message);
					}
					catch (Exception e2) {
						f.session.getAsyncRemote().sendText(message);
					}
				});
			}
		});
	}

	/**
	 * 存储用户（每个用户单独24小时过期）
	 */
	public void storeUser(String compositeId) {
		RedisTemplate<String, String> redisTemplate = SpringUtil.getBean(RedisTemplate.class);
		String[] parts = compositeId.split(",");
		if (parts.length < 3)
			return;

		String department = parts[0];
		String groupId = parts[1];
		String userId = parts[2];

		// 1. 构建Hash key：group:users:500229000000
		String hashKey = GROUP_USER_HASH + groupId;

		// 2. Hash field使用用户ID，value存储完整信息
		String userInfo = department + "," + groupId + "," + userId + "|" + System.currentTimeMillis();
		redisTemplate.opsForHash().put(hashKey, userId, userInfo);

		// 3. 为每个用户单独设置过期（使用一个有序集合来管理过期时间）
		String expireKey = USER_EXPIRE_SET + groupId + ":" + userId;
		String expireValue = compositeId;

		// 存储用户信息，24小时后自动过期
		redisTemplate.opsForValue().set(expireKey, expireValue, 24 * 60 * 60, TimeUnit.SECONDS);
	}

	/**
	 * 批量存储多个用户（每个用户单独过期）
	 */
	public void batchStoreUsers(List<String> compositeIds) {
		RedisTemplate<String, String> redisTemplate = SpringUtil.getBean(RedisTemplate.class);
		// 按分组ID分组存储
		Map<String, List<String[]>> groupMap = new HashMap<>();

		for (String compositeId : compositeIds) {
			String[] parts = compositeId.split(",");
			if (parts.length >= 3) {
				String groupId = parts[1];
				groupMap.computeIfAbsent(groupId, k -> new ArrayList<>()).add(parts);
			}
		}

		// 按分组批量存储
		for (Map.Entry<String, List<String[]>> entry : groupMap.entrySet()) {
			String groupId = entry.getKey();
			String hashKey = GROUP_USER_HASH + groupId;

			Map<String, String> userMap = new HashMap<>();

			for (String[] parts : entry.getValue()) {
				String userId = parts[2];
				String userInfo = parts[0] + "," + parts[1] + "," + userId + "|" + System.currentTimeMillis();
				userMap.put(userId, userInfo);

				// 为每个用户设置单独的过期key
				String expireKey = USER_EXPIRE_SET + groupId + ":" + userId;
				String compositeId = parts[0] + "," + parts[1] + "," + userId;
				redisTemplate.opsForValue().set(expireKey, compositeId, 24 * 60 * 60, TimeUnit.SECONDS);
			}

			// 批量存入Hash
			redisTemplate.opsForHash().putAll(hashKey, userMap);
		}
	}

	/**
	 * 续期用户（重新设置24小时）
	 */
	public void renewUser(String compositeId) {
		RedisTemplate<String, String> redisTemplate = SpringUtil.getBean(RedisTemplate.class);
		String[] parts = compositeId.split(",");
		if (parts.length < 3)
			return;

		String groupId = parts[1];
		String userId = parts[2];

		// 1. 更新Hash中的时间戳
		String hashKey = GROUP_USER_HASH + groupId;
		String oldUserInfo = (String) redisTemplate.opsForHash().get(hashKey, userId);

		if (oldUserInfo != null) {
			String[] infoParts = oldUserInfo.split("\\|");
			String newUserInfo = infoParts[0] + "|" + System.currentTimeMillis();
			redisTemplate.opsForHash().put(hashKey, userId, newUserInfo);
		}

		// 2. 续期过期key
		String expireKey = USER_EXPIRE_SET + groupId + ":" + userId;
		redisTemplate.expire(expireKey, 24 * 60 * 60, TimeUnit.SECONDS);
	}

	/**
	 * 用户主动退出/删除用户
	 */
	public void removeUser(String compositeId) {
		RedisTemplate<String, String> redisTemplate = SpringUtil.getBean(RedisTemplate.class);
		String[] parts = compositeId.split(",");
		if (parts.length < 3)
			return;

		String groupId = parts[1];
		String userId = parts[2];

		// 1. 从Hash中删除
		String hashKey = GROUP_USER_HASH + groupId;
		redisTemplate.opsForHash().delete(hashKey, userId);

		// 2. 删除过期key
		String expireKey = USER_EXPIRE_SET + groupId + ":" + userId;
		redisTemplate.delete(expireKey);
	}

	/**
	 * 检查用户是否过期（通过检查过期key是否存在）
	 */
	public boolean isUserActive(String groupId, String userId) {
		RedisTemplate<String, String> redisTemplate = SpringUtil.getBean(RedisTemplate.class);
		String expireKey = USER_EXPIRE_SET + groupId + ":" + userId;
		return Boolean.TRUE.equals(redisTemplate.hasKey(expireKey));
	}

	/**
	 * 获取用户剩余过期时间（秒）
	 */
	public Long getUserTTL(String groupId, String userId) {
		RedisTemplate<String, String> redisTemplate = SpringUtil.getBean(RedisTemplate.class);
		String expireKey = USER_EXPIRE_SET + groupId + ":" + userId;
		return redisTemplate.getExpire(expireKey, TimeUnit.SECONDS);
	}

	/**
	 * 清理过期的用户（手动清理，可作为定时任务）
	 */
	public void cleanupExpiredUsers(String groupId) {
		RedisTemplate<String, String> redisTemplate = SpringUtil.getBean(RedisTemplate.class);
		String hashKey = GROUP_USER_HASH + groupId;
		Map<Object, Object> entries = redisTemplate.opsForHash().entries(hashKey);

		if (entries == null || entries.isEmpty()) {
			return;
		}

		List<String> expiredUserIds = new ArrayList<>();

		for (Map.Entry<Object, Object> entry : entries.entrySet()) {
			String userId = (String) entry.getKey();
			String expireKey = USER_EXPIRE_SET + groupId + ":" + userId;

			// 检查过期key是否存在
			if (!Boolean.TRUE.equals(redisTemplate.hasKey(expireKey))) {
				expiredUserIds.add(userId);
			}
		}

		// 删除已过期的用户
		if (!expiredUserIds.isEmpty()) {
			Object[] userIdsArray = expiredUserIds.toArray();
			redisTemplate.opsForHash().delete(hashKey, userIdsArray);
			System.out.println("清理了 " + expiredUserIds.size() + " 个过期用户");
		}
	}

	/**
	 * 从Redis根据组ID获取所有用户ID
	 * @param groupId 组ID
	 * @return 用户ID列表
	 */
	public List<String> getUserIdsFromRedisByGroupId(String groupId) {
		RedisTemplate<String, String> redisTemplate = SpringUtil.getBean(RedisTemplate.class);
		String hashKey = GROUP_USER_HASH + groupId;

		// 获取Hash中的所有键（用户ID）
		Set<Object> keys = redisTemplate.opsForHash().keys(hashKey);

		return keys.stream().map(Object::toString).collect(Collectors.toList());
	}

	/**
	 * 从复合ID中提取组ID
	 * @param compositeId 格式：部门,组ID,用户ID
	 * @return 组ID，格式错误返回null
	 */
	public static String extractGroupId(String compositeId, Integer part) {
		if (compositeId == null || compositeId.isEmpty()) {
			return null;
		}

		String[] parts = compositeId.split(",");
		if (parts.length >= 3) {
			return parts[part]; // 中间的部分就是组ID
		}
		return null;
	}

}
