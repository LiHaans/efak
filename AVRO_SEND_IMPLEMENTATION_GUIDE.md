# Avro格式消息发送功能实现指南

## 已完成工作

### 1. Maven依赖添加 ✅
在 `efak-web/pom.xml` 中添加了以下依赖：

```xml
<!-- Avro序列化支持 -->
<dependency>
    <groupId>org.apache.avro</groupId>
    <artifactId>avro</artifactId>
    <version>1.11.3</version>
</dependency>

<!-- Kafka Avro Serializer和Schema Registry客户端 -->
<dependency>
    <groupId>io.confluent</groupId>
    <artifactId>kafka-avro-serializer</artifactId>
    <version>7.5.0</version>
</dependency>
```

**注意**：`kafka-avro-serializer` 需要Confluent Maven仓库。需要在pom.xml中添加：

```xml
<repositories>
    <repository>
        <id>confluent</id>
        <url>https://packages.confluent.io/maven/</url>
    </repository>
</repositories>
```

### 2. 数据模型更新 ✅
- ✅ `KafkaClusterInfo` 已包含 `schemaRegistryUrl` 字段
- ✅ `ClusterMapper` 已更新，支持查询和保存 `schema_registry_url`
- ✅ 数据库表 `ke_cluster` 已添加 `schema_registry_url` 字段

### 3. Avro序列化工具类 ✅
创建了 `AvroMessageSerializer.java` 工具类，提供：
- JSON到Avro GenericRecord的转换
- Schema验证
- 支持复杂类型：RECORD, ARRAY, MAP, UNION等

## 待完成工作

### 4. TopicServiceImpl中实现Avro发送逻辑

修改 `sendMessage` 方法，在1051行的TODO位置添加Avro处理逻辑：

```java
// 如果是Avro格式
if ("avro".equalsIgnoreCase(request.getFormat())) {
    // 验证Schema是否提供
    if (!StringUtils.hasText(request.getSchema())) {
        result.put("success", false);
        result.put("message", "Avro格式需要提供Schema");
        return result;
    }
    
    // 获取集群的Schema Registry URL
    org.kafka.eagle.dto.cluster.KafkaClusterInfo clusterInfo = 
        clusterMapper.findByClusterId(request.getClusterId());
    
    if (clusterInfo == null || !StringUtils.hasText(clusterInfo.getSchemaRegistryUrl())) {
        result.put("success", false);
        result.put("message", "集群未配置Schema Registry URL，请在集群设置中配置");
        return result;
    }
    
    try {
        // 验证消息是否符合Schema
        String validationError = AvroMessageSerializer.validateJsonAgainstSchema(
            messageContent, request.getSchema());
        if (validationError != null) {
            result.put("success", false);
            result.put("message", "消息不符合Schema: " + validationError);
            return result;
        }
    } catch (Exception e) {
        result.put("success", false);
        result.put("message", "Schema解析失败: " + e.getMessage());
        return result;
    }
}
```

### 5. 使用Schema Registry发送Avro消息

需要修改Producer配置使用Avro序列化器：

```java
// 根据格式选择不同的序列化器
if ("avro".equalsIgnoreCase(request.getFormat())) {
    props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
    props.put("value.serializer", "io.confluent.kafka.serializers.KafkaAvroSerializer");
    props.put("schema.registry.url", clusterInfo.getSchemaRegistryUrl());
    
    try (org.apache.kafka.clients.producer.KafkaProducer<String, GenericRecord> producer = 
            new org.apache.kafka.clients.producer.KafkaProducer<>(props)) {
        
        for (int i = 0; i < messageCount; i++) {
            try {
                // 转换JSON为GenericRecord
                GenericRecord avroRecord = AvroMessageSerializer.jsonToAvroRecord(
                    request.getMessage(), request.getSchema());
                
                // 构造ProducerRecord
                org.apache.kafka.clients.producer.ProducerRecord<String, GenericRecord> record;
                if (request.getPartition() != null) {
                    record = new org.apache.kafka.clients.producer.ProducerRecord<>(
                        request.getTopicName(), 
                        request.getPartition(), 
                        request.getKey(), 
                        avroRecord
                    );
                } else if (StringUtils.hasText(request.getKey())) {
                    record = new org.apache.kafka.clients.producer.ProducerRecord<>(
                        request.getTopicName(), 
                        request.getKey(), 
                        avroRecord
                    );
                } else {
                    record = new org.apache.kafka.clients.producer.ProducerRecord<>(
                        request.getTopicName(), 
                        avroRecord
                    );
                }
                
                producer.send(record).get();
                successCount++;
            } catch (Exception e) {
                log.error("发送Avro消息失败，第{}条: {}", i + 1, e.getMessage(), e);
                failCount++;
            }
        }
    }
}
```

### 6. 前端Schema输入区域

在 `topic-detail.html` 的发送消息对话框中，Avro格式时显示Schema输入：

```html
<!-- Avro Schema输入（仅Avro格式时显示） -->
<div class="mb-4" id="avroSchemaSection" style="display: none;">
    <label class="block text-sm font-medium text-gray-700 mb-2">
        Avro Schema <span class="text-red-500">*</span>
        <span class="text-xs text-gray-500">JSON格式的Avro Schema定义</span>
    </label>
    <textarea id="sendSchema" rows="6" 
        placeholder="{&quot;type&quot;: &quot;record&quot;, &quot;name&quot;: &quot;User&quot;, &quot;fields&quot;: [{&quot;name&quot;: &quot;name&quot;, &quot;type&quot;: &quot;string&quot;}]}"
        class="w-full px-3 py-2 border border-gray-300 rounded-lg font-mono text-sm"></textarea>
    <div class="mt-1 text-xs text-blue-600">
        Schema示例: {"type":"record","name":"User","fields":[{"name":"name","type":"string"},{"name":"age","type":"int"}]}
    </div>
</div>
```

### 7. 前端JavaScript修改

修改 `topic-detail.js` 中的 `onFormatChange` 函数：

```javascript
function onFormatChange() {
    const format = document.querySelector('input[name="messageFormat"]:checked').value;
    const messageContent = document.getElementById('sendMessageContent');
    const avroSchemaSection = document.getElementById('avroSchemaSection');
    
    if (format === 'json') {
        messageContent.placeholder = '{"example": "message"}';
        avroSchemaSection.style.display = 'none';
    } else if (format === 'avro') {
        messageContent.placeholder = '{"name": "John", "age": 30}';
        avroSchemaSection.style.display = 'block';
    }
}
```

修改 `sendMessage` 函数，添加Schema参数：

```javascript
// 可选参数
if (partition !== '') {
    requestData.partition = parseInt(partition);
}
if (key && key.trim() !== '') {
    requestData.key = key.trim();
}
// Avro格式需要Schema
if (format === 'avro') {
    const schema = document.getElementById('sendSchema').value;
    if (!schema || schema.trim() === '') {
        showNotification('Avro格式需要提供Schema', 'error');
        return;
    }
    requestData.schema = schema.trim();
}
```

### 8. 集群配置页面Schema Registry URL输入

需要在集群管理页面添加Schema Registry URL配置字段。

查找集群配置相关的HTML文件（通常是 `clusters.html` 或类似文件），在认证配置后添加：

```html
<div class="mb-4">
    <label class="block text-sm font-medium text-gray-700 mb-2">
        Schema Registry URL
        <span class="text-xs text-gray-500">(用于Avro消息序列化)</span>
    </label>
    <input type="url" id="schemaRegistryUrl" 
        placeholder="http://localhost:8081"
        class="w-full px-3 py-2 border border-gray-300 rounded-lg">
    <div class="mt-1 text-xs text-gray-500">
        Confluent Schema Registry服务地址，留空则不支持Avro格式消息
    </div>
</div>
```

## Avro Schema示例

### 简单记录
```json
{
  "type": "record",
  "name": "User",
  "fields": [
    {"name": "name", "type": "string"},
    {"name": "age", "type": "int"}
  ]
}
```

### 带可选字段
```json
{
  "type": "record",
  "name": "Product",
  "fields": [
    {"name": "id", "type": "string"},
    {"name": "name", "type": "string"},
    {"name": "price", "type": "double"},
    {"name": "description", "type": ["null", "string"], "default": null}
  ]
}
```

### 嵌套记录
```json
{
  "type": "record",
  "name": "Order",
  "fields": [
    {"name": "orderId", "type": "string"},
    {"name": "customer", "type": {
      "type": "record",
      "name": "Customer",
      "fields": [
        {"name": "name", "type": "string"},
        {"name": "email", "type": "string"}
      ]
    }},
    {"name": "items", "type": {
      "type": "array",
      "items": "string"
    }}
  ]
}
```

## 测试步骤

1. **配置Schema Registry**
   - 在集群配置中添加Schema Registry URL（如：`http://localhost:8081`）

2. **准备Avro Schema**
   - 定义符合业务需求的Avro Schema（JSON格式）

3. **准备JSON消息**
   - 确保JSON消息的字段与Schema定义一致

4. **发送测试消息**
   - 选择Avro格式
   - 输入Schema
   - 输入符合Schema的JSON消息
   - 点击发送

5. **验证**
   - 检查发送结果
   - 使用Kafka消费者验证消息是否正确序列化

## 注意事项

1. **Confluent Maven仓库**：需要在pom.xml中添加Confluent仓库配置
2. **Schema Registry连接**：确保应用能够访问Schema Registry服务
3. **Schema兼容性**：Schema Registry默认会进行兼容性检查
4. **性能考虑**：Avro序列化比JSON略慢，但消息体积更小
5. **错误处理**：Schema不匹配或Schema Registry不可用时要提供清晰的错误信息

## 下一步

1. 添加Confluent Maven仓库配置
2. 完成TopicServiceImpl中的Avro发送逻辑
3. 完成前端Schema输入区域
4. 完成集群配置页面的Schema Registry URL字段
5. 编译测试
6. 提交代码
