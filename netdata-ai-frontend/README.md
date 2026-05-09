# NetData 智能运维问答系统前端

基于 Vue3 + TypeScript + Element Plus 的智能运维问答系统前端。

## 技术栈

- **框架**: Vue 3.4 + TypeScript
- **构建工具**: Vite 5
- **UI 组件库**: Element Plus
- **状态管理**: Pinia
- **路由**: Vue Router 4
- **HTTP 客户端**: Axios
- **Markdown 渲染**: markdown-it
- **代码高亮**: highlight.js

## 功能模块

- **智能问答**: 类 ChatGPT 的对话界面
- **告警仪表板**: 实时告警监控
- **知识库管理**: 文档上传和管理
- **执行审批**: 命令执行审批流程

## 开发命令

```bash
# 安装依赖
npm install

# 启动开发服务器
npm run dev

# 构建生产版本
npm run build

# 预览生产版本
npm run preview

# 类型检查
npm run type-check
```

## 项目结构

```
src/
├── api/          # API 接口
├── assets/       # 静态资源
├── components/   # 公共组件
├── router/       # 路由配置
├── stores/       # Pinia 状态管理
├── types/        # TypeScript 类型定义
└── views/        # 页面视图
```
