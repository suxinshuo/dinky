---
sidebar_position: 73
title: 1.2.5 release
---

| Dinky 版本 | Flink 版本 | 二进制程序                                                                                                                                   | Source                                                                                |
|----------|----------|-----------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------|
| 1.2.5    | 1.14     | [dinky-release-1.14-1.2.5.tar.gz](https://github.com/DataLinkDC/dinky/releases/download/v1.2.5/dinky-release-1.14-1.2.5.tar.gz) | [Source code (zip)](https://github.com/DataLinkDC/dinky/archive/refs/tags/v1.2.5.zip) |
| 1.2.5    | 1.15     | [dinky-release-1.15-1.2.5.tar.gz](https://github.com/DataLinkDC/dinky/releases/download/v1.2.5/dinky-release-1.15-1.2.5.tar.gz) | [Source code (zip)](https://github.com/DataLinkDC/dinky/archive/refs/tags/v1.2.5.zip) |
| 1.2.5    | 1.16     | [dinky-release-1.16-1.2.5.tar.gz](https://github.com/DataLinkDC/dinky/releases/download/v1.2.5/dinky-release-1.16-1.2.5.tar.gz) | [Source code (zip)](https://github.com/DataLinkDC/dinky/archive/refs/tags/v1.2.5.zip) |
| 1.2.5    | 1.17     | [dinky-release-1.17-1.2.5.tar.gz](https://github.com/DataLinkDC/dinky/releases/download/v1.2.5/dinky-release-1.17-1.2.5.tar.gz) | [Source code (zip)](https://github.com/DataLinkDC/dinky/archive/refs/tags/v1.2.5.zip) |
| 1.2.5    | 1.18     | [dinky-release-1.18-1.2.5.tar.gz](https://github.com/DataLinkDC/dinky/releases/download/v1.2.5/dinky-release-1.18-1.2.5.tar.gz) | [Source code (zip)](https://github.com/DataLinkDC/dinky/archive/refs/tags/v1.2.5.zip) |
| 1.2.5    | 1.19     | [dinky-release-1.19-1.2.5.tar.gz](https://github.com/DataLinkDC/dinky/releases/download/v1.2.5/dinky-release-1.19-1.2.5.tar.gz) | [Source code (zip)](https://github.com/DataLinkDC/dinky/archive/refs/tags/v1.2.5.zip) |
| 1.2.5    | 1.20     | [dinky-release-1.20-1.2.5.tar.gz](https://github.com/DataLinkDC/dinky/releases/download/v1.2.5/dinky-release-1.20-1.2.5.tar.gz) | [Source code (zip)](https://github.com/DataLinkDC/dinky/archive/refs/tags/v1.2.5.zip) |

## Dinky-1.2.5 发行说明

### 升级说明

:::warning 重要
v1.2.5 替换所有 Dinky 相关 jar 后直接重启可自动升级。
:::

### 修复
- 刷新作业时忽略租户条件
- 修复添加数据库时缓存操作返回空键的问题
- 将 Guava 降级到 31.1-jre 以修复 CDC 管道问题
- 修复 “无法回收处于自动提交模式的数据库：[数据库名]” 的问题
- 修复角色菜单更新问题
- 提交 CDCSOURCE 作业以同步表名以数字开头的表中的数据后出现异常
- 为 jar SQL 添加结束时间

### 优化
- Dockerfile 保留 flink-table-planner-loader.jar
- 在 Kubernetes 上的 Flink 中添加一个选项，用于控制是否包含 OwnerReference
- 统一 Hadoop 版本定义
- 将中文标点替换为英文标点并修正拼写错误
