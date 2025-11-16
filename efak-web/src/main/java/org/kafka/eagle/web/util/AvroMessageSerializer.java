package org.kafka.eagle.web.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

/**
 * <p>
 * Avro消息序列化工具类
 * 用于将JSON消息转换为Avro GenericRecord
 * </p>
 * @author Mr.SmartLoli
 * @since 2025/11/16
 * @version 5.0.0
 */
@Slf4j
public class AvroMessageSerializer {
    
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * 将JSON字符串和Avro Schema转换为GenericRecord
     *
     * @param jsonMessage JSON格式的消息内容
     * @param schemaString Avro Schema字符串
     * @return GenericRecord对象
     * @throws Exception 转换失败时抛出异常
     */
    public static GenericRecord jsonToAvroRecord(String jsonMessage, String schemaString) throws Exception {
        // 解析Schema
        Schema schema = new Schema.Parser().parse(schemaString);
        
        // 解析JSON消息
        JsonNode jsonNode = objectMapper.readTree(jsonMessage);
        
        // 创建GenericRecord
        GenericRecord record = new GenericData.Record(schema);
        
        // 遍历Schema字段，从JSON中提取值
        for (Schema.Field field : schema.getFields()) {
            String fieldName = field.name();
            JsonNode fieldValue = jsonNode.get(fieldName);
            
            if (fieldValue != null && !fieldValue.isNull()) {
                Object value = extractValue(fieldValue, field.schema());
                record.put(fieldName, value);
            } else if (isOptional(field.schema())) {
                // 可选字段设置为null
                record.put(fieldName, null);
            } else {
                // 必填字段但JSON中没有值，抛出异常
                throw new IllegalArgumentException(
                    String.format("必填字段 '%s' 在JSON消息中不存在", fieldName));
            }
        }
        
        return record;
    }
    
    /**
     * 根据Avro Schema类型从JsonNode中提取值
     */
    private static Object extractValue(JsonNode node, Schema schema) throws Exception {
        Schema.Type type = schema.getType();
        
        // 处理Union类型（通常用于可选字段）
        if (type == Schema.Type.UNION) {
            Schema actualSchema = getNonNullSchema(schema);
            if (actualSchema != null) {
                return extractValue(node, actualSchema);
            }
            return null;
        }
        
        // 根据类型提取值
        switch (type) {
            case STRING:
                return node.asText();
            case INT:
                return node.asInt();
            case LONG:
                return node.asLong();
            case FLOAT:
                return (float) node.asDouble();
            case DOUBLE:
                return node.asDouble();
            case BOOLEAN:
                return node.asBoolean();
            case BYTES:
                return node.binaryValue();
            case ARRAY:
                GenericData.Array<Object> array = new GenericData.Array<>(
                    node.size(), schema);
                Schema elementSchema = schema.getElementType();
                for (JsonNode element : node) {
                    array.add(extractValue(element, elementSchema));
                }
                return array;
            case RECORD:
                GenericRecord record = new GenericData.Record(schema);
                for (Schema.Field field : schema.getFields()) {
                    JsonNode fieldValue = node.get(field.name());
                    if (fieldValue != null && !fieldValue.isNull()) {
                        record.put(field.name(), extractValue(fieldValue, field.schema()));
                    }
                }
                return record;
            case MAP:
                // MAP类型处理
                java.util.Map<String, Object> map = new java.util.HashMap<>();
                Schema valueSchema = schema.getValueType();
                node.fields().forEachRemaining(entry -> {
                    try {
                        map.put(entry.getKey(), extractValue(entry.getValue(), valueSchema));
                    } catch (Exception e) {
                        log.error("处理MAP字段失败: {}", e.getMessage());
                    }
                });
                return map;
            case ENUM:
                return new GenericData.EnumSymbol(schema, node.asText());
            case NULL:
                return null;
            default:
                throw new UnsupportedOperationException(
                    "不支持的Avro类型: " + type);
        }
    }
    
    /**
     * 判断Schema是否为可选类型（Union包含null）
     */
    private static boolean isOptional(Schema schema) {
        if (schema.getType() != Schema.Type.UNION) {
            return false;
        }
        
        for (Schema type : schema.getTypes()) {
            if (type.getType() == Schema.Type.NULL) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 从Union类型中获取非null的Schema
     */
    private static Schema getNonNullSchema(Schema unionSchema) {
        if (unionSchema.getType() != Schema.Type.UNION) {
            return unionSchema;
        }
        
        for (Schema schema : unionSchema.getTypes()) {
            if (schema.getType() != Schema.Type.NULL) {
                return schema;
            }
        }
        return null;
    }
    
    /**
     * 验证JSON消息是否符合Avro Schema
     *
     * @param jsonMessage JSON消息
     * @param schemaString Avro Schema字符串
     * @return 验证结果消息，null表示验证通过
     */
    public static String validateJsonAgainstSchema(String jsonMessage, String schemaString) {
        try {
            jsonToAvroRecord(jsonMessage, schemaString);
            return null; // 验证通过
        } catch (Exception e) {
            return e.getMessage();
        }
    }
}
