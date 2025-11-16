# 前端同步Topic功能使用说明

## 功能概述

在主题管理页面添加了"同步Topic"按钮，可以一键从Kafka集群同步所有topic到数据库。

## 已修改的文件

### 1. topics.html

**文件路径**: `efak-web/src/main/resources/templates/view/topics.html`

**修改位置**: 第1221-1225行

**添加内容**: 在"创建主题"按钮旁边添加了"同步Topic"按钮

```html
<button id="sync-topics-btn"
        class="flex items-center px-4 py-2 bg-gradient-to-r from-green-500 to-green-600 text-white rounded-lg hover:from-green-600 hover:to-green-700 focus:outline-none focus:ring-2 focus:ring-green-500/50 focus:ring-offset-2 shadow-lg transform transition-all duration-200 ease-in-out hover:-translate-y-0.5">
    <i class="fa fa-refresh mr-2"></i>
    同步Topic
</button>
```

### 2. topics.js

**文件路径**: `efak-web/src/main/resources/statics/js/system/topics.js`

**修改内容**:

#### 2.1 在 init() 方法中绑定按钮事件

```javascript
// 绑定同步按钮
const syncBtn = document.getElementById('sync-topics-btn');
if (syncBtn) {
    syncBtn.addEventListener('click', () => this.syncTopics());
}
```

#### 2.2 新增 syncTopics() 方法

```javascript
// 同步Kafka集群中的所有Topic到数据库
async syncTopics() {
    try {
        const clusterId = this.getClusterId();
        if (!clusterId) {
            this.showAlert('请先在页面中选择集群后再进行同步', 'warning');
            return;
        }

        // 按钮loading状态
        const syncBtn = document.getElementById('sync-topics-btn');
        if (syncBtn) {
            syncBtn.disabled = true;
            syncBtn.innerHTML = '<i class="fa fa-spinner fa-spin mr-2"></i> 同步中...';
        }

        this.showLoading();

        const response = await fetch(`/topic/api/sync?clusterId=${encodeURIComponent(clusterId)}`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            }
        });

        const result = await response.json();

        if (result.success) {
            const added = result.added || 0;
            const updated = result.updated || 0;
            const total = result.total || 0;
            this.showAlert(`同步完成：新增 ${added}，更新 ${updated}，共 ${total}`, 'success');
            // 刷新表格
            await this.loadTopicData();
        } else {
            this.showAlert('同步失败：' + (result.message || '服务器错误'), 'error');
        }
    } catch (error) {
        this.showAlert('同步异常：' + error.message, 'error');
    } finally {
        // 恢复按钮
        const syncBtn = document.getElementById('sync-topics-btn');
        if (syncBtn) {
            syncBtn.disabled = false;
            syncBtn.innerHTML = '<i class="fa fa-refresh mr-2"></i> 同步Topic';
        }
        this.hideLoading();
    }
}
```

## 功能特性

### 1. 按钮样式

- **位置**: 主题管理页面，"创建主题"按钮旁边
- **颜色**: 绿色渐变 (from-green-500 to-green-600)
- **图标**: Font Awesome 的 `fa-refresh` 旋转图标
- **悬停效果**: 按钮向上轻微移动，颜色加深

### 2. 交互流程

1. **点击按钮**
   - 检查是否已选择集群ID
   - 如果没有选择集群，显示警告提示

2. **同步中状态**
   - 按钮变为禁用状态
   - 按钮文字变为"同步中..."
   - 图标变为旋转的spinner
   - 页面显示加载遮罩

3. **同步完成**
   - 显示同步结果通知（新增、更新、总数）
   - 自动刷新表格数据
   - 恢复按钮正常状态
   - 隐藏加载遮罩

4. **错误处理**
   - 显示错误提示
   - 恢复按钮正常状态
   - 隐藏加载遮罩

### 3. 通知样式

使用项目统一的 `showAlert()` 方法显示通知：

- **成功**: 绿色背景 + 成功图标
- **失败**: 红色背景 + 错误图标
- **警告**: 黄色背景 + 警告图标
- **自动消失**: 3秒后自动隐藏

## 使用方法

### 步骤1: 打开主题管理页面

访问: `http://localhost:28888/topics`

### 步骤2: 选择集群

确保页面已选择要同步的Kafka集群（clusterId）

### 步骤3: 点击同步按钮

点击"同步Topic"按钮，等待同步完成

### 步骤4: 查看结果

- 同步成功会显示绿色通知，包含统计信息
- 表格会自动刷新显示最新的topic列表

## 测试场景

### 场景1: 正常同步

**前提条件**:
- Kafka集群正常运行
- 集群中有多个topic
- 数据库中部分topic不存在

**预期结果**:
- 显示"同步完成：新增 X，更新 Y，共 Z"
- 表格刷新显示所有topic

### 场景2: 没有选择集群

**前提条件**:
- 页面未选择clusterId

**预期结果**:
- 显示黄色警告："请先在页面中选择集群后再进行同步"
- 不发起同步请求

### 场景3: 集群无topic

**前提条件**:
- Kafka集群为空，没有topic

**预期结果**:
- 显示"同步完成：新增 0，更新 0，共 0"
- 表格显示"暂无数据"

### 场景4: 网络错误

**前提条件**:
- 后端服务异常或网络断开

**预期结果**:
- 显示红色错误提示："同步异常：错误信息"
- 按钮恢复正常状态

## 注意事项

1. **集群选择**: 必须先选择集群才能进行同步
2. **同步时间**: 根据topic数量不同，同步时间可能较长，请耐心等待
3. **重复点击**: 同步过程中按钮会被禁用，防止重复点击
4. **数据一致性**: 同步会自动处理新增和更新操作，保证数据一致性
5. **错误重试**: 如果同步失败，可以重新点击按钮再次尝试

## UI截图说明

### 同步按钮位置

```
┌─────────────────────────────────────────────────┐
│  主题详细信息                                        │
│  [创建主题]  [同步Topic]                            │
├─────────────────────────────────────────────────┤
│  主题名称  │  分区数  │  副本数  │  ...           │
├─────────────────────────────────────────────────┤
│  test001   │    1     │    1     │  ...          │
└─────────────────────────────────────────────────┘
```

### 同步中状态

```
[🔄 同步中...]  （按钮禁用，灰色）
```

### 同步完成通知

```
┌─────────────────────────────────────┐
│ ✅ 同步完成：新增 5，更新 3，共 8       │
└─────────────────────────────────────┘
```

## 相关后端API

- **接口地址**: `POST /topic/api/sync`
- **请求参数**: `clusterId` (必填)
- **响应格式**:
```json
{
  "success": true,
  "message": "同步完成",
  "added": 5,
  "updated": 3,
  "total": 8,
  "errors": 0,
  "errorDetails": []
}
```

## 技术实现细节

### 1. 获取集群ID

```javascript
const clusterId = this.getClusterId();
```

该方法从页面上下文中获取当前选择的集群ID

### 2. 异步请求

使用 Fetch API 发送 POST 请求：
- 设置正确的 Content-Type
- 使用 async/await 处理异步
- try-catch 捕获错误

### 3. UI状态管理

- 使用 disabled 属性禁用按钮
- 动态修改 innerHTML 显示loading状态
- 使用 finally 确保状态恢复

### 4. 用户反馈

- Loading遮罩：`this.showLoading()`
- 成功通知：`this.showAlert(message, 'success')`
- 错误通知：`this.showAlert(message, 'error')`
- 自动刷新：`this.loadTopicData()`

## 浏览器兼容性

- Chrome 90+
- Firefox 88+
- Safari 14+
- Edge 90+

支持所有现代浏览器，使用标准 Fetch API 和 ES6+ 语法

## 后续优化建议

1. **进度显示**: 添加同步进度百分比显示
2. **详细日志**: 显示同步过程中的详细日志
3. **选择性同步**: 支持选择特定topic进行同步
4. **定时同步**: 支持设置自动定时同步
5. **冲突处理**: 优化数据冲突的处理策略

---

更新时间: 2025-11-16
