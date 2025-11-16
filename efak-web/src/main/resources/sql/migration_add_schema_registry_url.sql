-- 在ke_cluster表中添加schema_registry_url字段
-- 用于配置Avro消息发送时的Schema Registry地址

ALTER TABLE `ke_cluster` 
ADD COLUMN `schema_registry_url` VARCHAR(500) NULL COMMENT 'Schema Registry URL（用于Avro消息序列化）' AFTER `auth_config`;
