# Android 客户端

统一仓库的android/与独立Android仓库的source/均为Gradle根目录；本README在独立仓库位于仓库根目录。当前开发以统一项目为准，不在两份副本各自开发；交付不包含Git历史、产物、手机数据库或真实凭据。

当前App **1.1.2 / versionCode4**，Room11。使用JDK17和本机Android SDK，在上述Gradle根目录执行 `gradlew.bat testDebugUnitTest assembleDebug`；SDK通过ANDROID_HOME或忽略的local.properties设置。OTA/签名凭据只在本机注入，生产配置通过PANDA_RELEASE_KEYSTORE_PROPERTIES读取仓库外文件，模板不是实际凭据。

接手与真机记录见本目录[HANDOFF.md](HANDOFF.md)。公共架构和协议以[CODEX_START_HERE.md](https://github.com/linckr/NRF52xxx-FieldTemp/blob/main/CODEX_START_HERE.md)与[PROTOCOL.md](https://github.com/linckr/NRF52xxx-FieldTemp/blob/main/PROTOCOL.md)为准；这些链接兼容统一android/与独立source/布局。

0x40能力位与5 B HISTORY请求尾03共同选择V3 14 B；旧4 B请求保持12 B。0x80 + HISTORY保留命令确认后启用逐设备本地持续3000策略，不向CLEAR发送保留请求，不按版本或包长猜布局。完整END、3000唯一wire时间戳、前后8 B INFO稳定、upsert成功且目标会话仍有效才事务精简模块记录；失败不删，最多重试一次。Room11明确手机采样来源，保护有/无GPS手机行、隔离记录及其他设备；不能按时间排序近似裁剪。

124项JVM和10项isolated Room测试通过；1.1.2生产APK v2/RSA3072验签通过、未安装。realme到小米迁移及精简已应用，持续策略retain真机36.388 s通过：硬件/App模块各3000、retain时手机样本208；最新Live连续同步/断连重连回归171.105 s通过。最终一致归档模块3000/GPS187/无GPS手机31，active3218、隔离22484、未来/重复0。

硬件冷启动保留/raw集合及P3四阶段流程真实去电已有恢复证据，不证明NOR/NVMC忙脉冲断电。设备仍开发信任链1.0.9、手机Debug；生产MCUboot公钥尚未部署、候选产物入口为 [v1.0.9-rc.1](https://github.com/linckr/NRF52xxx-FieldTemp/releases/tag/v1.0.9-rc.1)，发布状态以页面为准，INIT22根因与ADC校准仍待解决。完整档案、手机标识和凭据不上传；详细证据及剩余任务见交接文档。

### 最终Live与一致归档验收

最终真机Live回归171.105 s通过：两次连续全量各3000、立即重试、收到部分记录后断连且全部既有模块ID未删除、重连及再次同步完成3000；仍保留窗口内记录ID/核心字段和所有既有手机样本，无重复/未来时间。重连首轮观察到0条历史、随后一次自动全量重试成功；本轮已由有界重试覆盖，但首次重连延迟为Medium性能边界，根因未定位。

最终只读一致归档（2026-10-10约01:12）SQLite integrity ok：模块3000、GPS187、无GPS手机样本31（受保护手机样本共218）、active3218、quarantine22484，未来时间0、模块重复0。Live期间VM电压开始2.886 V、结束2.868 V（本轮观测，不是ADC校准证明）。App已恢复运行；手机临时USB亮屏设置已恢复原值。私人数据库、档案路径、标识与归档内容不进Git。
