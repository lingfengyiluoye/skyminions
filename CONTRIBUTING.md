# 🤝 贡献指南

感谢你对 SkyMinions 的关注！

## 开发环境

```bash
# 克隆仓库
git clone https://github.com/lingfengyiluoye/skyminions.git
cd skyminions

# 构建
mvn clean package

# 运行测试
mvn test
```

**要求：** JDK 21+、Maven 3.8+

## 代码规范

- **全量中文化**：游戏内显示内容禁止英文
- **去斜体**：Adventure 文本必须 `decoration(TextDecoration.ITALIC, false)`
- **GUI 模板化**：槽位/材质走 `gui.yml`，禁止硬编码常量
- **配置热重载**：所有配置经 `ConfigProvider` 实时读取
- **零空 catch**：捕获后必须记录 + 优雅降级
- **主线程安全**：方块操作/Inventory 在主线程，IO 走虚拟线程

## 提交规范

```
feat: 新增 XX 仆从类型
fix: 修复 XX 离线结算问题
docs: 更新 README 文档
refactor: 重构 XX 服务层
test: 新增 XX 单元测试
```

## 新增仆从类型

1. 在 `config.yml` 的 `types:` 段添加配置
2. 在 `gui.yml` 添加对应文案模板
3. 在 `messages.yml` 添加消息（如需要）
4. 运行 `mvn test` 确保测试通过
5. 在游戏内 `/minion reload` 热重载验证

## 报告问题

请使用 [GitHub Issues](https://github.com/lingfengyiluoye/skyminions/issues) 报告问题，包含：

- Paper 版本
- 插件版本
- 错误日志（pastebin 链接）
- 复现步骤
