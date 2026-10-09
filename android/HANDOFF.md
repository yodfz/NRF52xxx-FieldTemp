# Android 接手与同步修复（2026-10-09）

当前代码是事实源。统一项目位于 https://github.com/linckr/NRF52xxx-FieldTemp ，其中 android/ 是直接可构建的 Gradle 根目录；独立 Android 仓库保留 source/ 根目录，用于向 yodfz/PandaThemperature-Android 提交上游 PR。不要在两处各自独立修改；同步时逐文件检查差异。

## 实际修复和协议边界

设备 patch=8（1.0.8）使用 12 字节 V2 历史记录；旧安装 APK 把 patch>=3 当成 14 字节 V3，60 字节包错位解析，产生 2043/2044 年等异常数据。现有 Profile 工厂已正确，必须安装新 APK，不能只凭 versionName 判断源码版本。

本次增加连续 60 秒无记录进展才超时的监控、未来时间增量起点回退、按设备+时间戳更新模块历史（保留 GPS 行）、结束清理期间持有会话、断连失效/取消旧任务、旧包与重复 END 隔离、模块进度排除 GPS、本会话接收计数、Room 提交后才清空缓冲和实时通知确认 5 秒超时。修复新增会话字段后置造成的冷启动连接监听退出：HistorySyncSession 必须在 init 之前初始化。

上述连接修复批次未改变BLE UUID、记录布局、OTA命令或数据库schema/version。后续P2已增加明确能力协商：0x40启用V3的5 B请求、0x80启用保留命令，仍不能由版本号或包长度推断。OTA 文件仍为 zephyr.signed.bin，信任边界仍为 MCUboot ECDSA-P256，OTA auth key 仅减少误触。

## 已验证

2026-10-09 本次提交的源码（提交前基线 29a9e9a），JDK17 构建 testDebugUnitTest / assembleDebug / assembleDebugAndroidTest 成功：105 项单元测试，0 失败/错误。手机 6 项真实 Room 内存测试及 1 项冷启动测试通过；显式启用的 1 项真实 BLE 回归在 193.298 秒内完成两次连续全量、传输中断连、重连自动同步和立即增量重试，验证重复为 0、既有模块记录 ID 和 GPS 行保留。

安装的是调试签名 APK，不是生产签名发布。设备状态版本为 patch=8，未重新烧录或读取设备固件二进制 hash，不能把固件源码 HEAD 45439cf 当成此次重新烧录验证结果。

用户授权后的本机数据库恢复归档了 22,484 条旧错位记录；此恢复只针对该手机精确快照，没有加入 App 自动迁移或通用按年份删除功能。最终 00:43:05 只读核对：SQLite integrity ok，有效 33,476 条（模块 33,382、GPS 94），未来年份/模块重复均为 0，异常归档保留。数据库、APK、恢复脚本和个人信息不随提交上传。

电池供电读数从约 2.989 V 到 2026-10-09 00:43 的 2.930 V，下降约 59 mV；万用表精度、容量和续航仍未验证。该批次的手机端OTA与真实断电矩阵待完成；生产签名的新阶段状态见下节。

## 回归入口

在 Gradle 根目录使用 JDK17：

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest --no-daemon --console=plain
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.pandatemperature.data.database.HistoryRecordDaoTest,com.example.pandatemperature.MainViewModelColdStartTest
```

LiveHistorySyncTest 默认跳过；仅在明确允许真实设备同步并提供 liveBle=true 时运行。它不全量清空任一端数据，但会连接设备、对时、下载历史和主动断连重连；已明确启用持续策略时，成功稳定会话会合法精简窗口外模块行。冷启动测试模拟内存状态，不打开硬件连接。

正式开发不要提交 local.properties、ota.properties、keystore.properties、签名密钥或手机数据库；仅提交对应 example 模板。

## P2 / 持续3000条与生产Release阶段（设备回读与冷启动持久性通过，最新Live回归通过）

当前源码versionCode4 / versionName1.1.2；124项JVM及10项Room测试通过，生产签名Release APK已验签。
生产Release APK尚未安装；手机仍Debug APK，开发信任链1.0.9已升级并逐字节验证。

状态byte5 bit6(0x40)显式选V3；HISTORY请求为timestamp LE32加03，14 B布局是V2加uint16 mV。
无该位仍V2/旧ESS V1；旧存量14 B回读时电压FFFF代表未知，不用当前电压回填。
bit7(0x80)支持HISTORY请求[3000 LE32,04]，当前固件仅支持该数量。
维护期间状态byte4 bit2置位，须等其清除并核对HISTORY_INFO总数/起点，再全量回读。
任何保留失败均不得回退到CLEAR特征，否则旧固件会全量清空。

维护真机测试默认跳过，只有明确授权及显式maintenanceAction参数才运行。
archive_sync是先完整同步供本机归档；retain才发设备保留命令，不等于手机数据库已删除旧数据。
手机数据库裁剪须在完整快照留档后单独执行事务，并验证保留3000条及原记录一致性。
本机完整原始档案已生成，硬件和手机模块持续保留3000条的retain验收通过；迁移与精简已应用；私人数据库、归档、APK与凭据不上传Git。

生产keystore与ECDSA密钥均在仓库外。Android通过PANDA_RELEASE_KEYSTORE_PROPERTIES读取外置配置，
只记录变量名，不记录真实凭据路径或内容。生产APK签名不改变MCUboot信任：首次生产公钥部署需SWD，
当前设备OTA必须仍使用其已有公钥对应的签名私钥。

Firmware v9开发构建Flash151540 B / signed152203 B / RAM23080 B，相对v8增加Flash2012 B、RAM128 B；RAM余1496 B，MCUboot余892 B，分区保持原布局。
native生产C故障注入0 failures；P2电压/硬件保留/raw集合及P3四阶段流程边界真实去电已验证，冷启动保留持久性已验证；忙脉冲断电与生产固件部署仍待验证，数据库迁移已完成，最新Live回归通过。


### 2026-10-09 OTA / P2 历史实证（当前状态见下节）

手机使用正式 Android OTA 代码上传开发信任链的 1.0.9 `zephyr.signed.bin`（152,203 B）。升级前设备 App 和 MCUboot 字节匹配 `build-v8`；内部 Flash、UICR、外部 secondary/NVS/history 与完整手机数据库均已本机归档，不入 Git。

| P3 场景 | 已取得的证据与结果 | 边界 |
|---|---|---|
| END 后、TRIGGER 前 | 初次电池断电后主槽163,840 B不变。隔离整根SWD后重复断电，BLE及同镜像续传VERIFY通过（25.588 s） | 初次SWD仍连接的后续启动曾出现W25Q64 init_res=22；原因未定，保留风险 |
| 部分写入 | confirmed=32,768/152,203 B、未END/TRIGGER时真实电池断电；续传VERIFY通过（37.018 s） | 证明部分上传期间断电恢复，不证明SPI写忙脉冲被切断 |
| 擦除流程 | 第二次断电后secondary前143,360 B已擦除、镜像尾部8,192 B仍保留；重新上传VERIFY通过（48.926 s） | 证明擦除流程中断，不证明NOR WIP脉冲中断 |
| MCUboot搬运 | copy3快照前1,024 B匹配候选、完整镜像未完成；CPU暂停后用户拔整根SWD和电池5 s；重启主槽候选152,203 B逐字节匹配，MCUboot32 KiB不变，1.0.9、VTOR=0x8200、CFSR/HFSR=0 | 调试器暂停的搬运流程遭遇真实去电，不等于NVMC写脉冲中断 |

升级后完整历史同步299.778 s通过，实际接收33,288条；已有传感器核心/GPS保护、无重复和无未来时间检查通过。能力字节255（0xFF）；9条历史电压为2.851–2.876 V，实时VM读数2.871 V（VM状态，不作为fresh raw证明）。旧存量电压未知，不用当前电压回填。

硬件保留测试90.353 s通过：维护busy清除、设备count=3000、全量回读3000。独立BLE raw捕获38.416 s通过：3000条V3、42,000 B、真实END、前后8 B HISTORY_INFO一致。手机数据库副本已按这些raw记录精确验证3000条，保留GPS180条及quarantine22,484条；**当时副本尚未应用；迁移/精简后续进展见当前验收状态，手机仍安装Debug APK**。

旧1.0.8测试序列中硬件尾部14条被擦除/覆盖；这些记录的timestamp及传感器数值14/14存在于本机完整App归档。原档NVS写头99:14、擦除后快照99:3，oldest均0；事故瞬间检查点未知，不能断言为0。旧scan在检查点落后、恢复被触发时漏掉next_sector的部分数据，与该损失一致。实际C回归覆盖stale99:0+14及stale99:14+28：旧函数回退99:0，新函数分别恢复99:14/99:28、追加后全部原行保留且零擦除。新策略冷启动持久性现已通过隔离SWD后的真实电池断电、全量回读及独立raw验证。

生产ECDSA及Android签名资产在仓库外，生产APK验签通过；**生产MCUboot公钥信任尚未部署、生产APK尚未安装**。当前OTA沿用设备原公钥对应的开发信任链。候选产物入口为 [v1.0.9-rc.1](https://github.com/linckr/NRF52xxx-FieldTemp/releases/tag/v1.0.9-rc.1)，发布状态以页面为准；两个upstream PR #2在本轮记录时OPEN、未合并，后续须实时查询。迁移与精简已应用，持续策略retain真机通过；最新1.1.2 Live回归171.105 s通过，最终归档模块3000/受保护手机样本218。

### 2026-10-10 当前验收状态：App 1.1.2 / 持续3000条

当前App为versionCode4 / versionName1.1.2、Room11，手机安装Debug APK；Firmware为开发信任链1.0.9。124项JVM单元测试、10项isolated Room测试通过；生产1.1.2 APK v2/RSA3072验签通过、证书未变，尚未安装生产APK。最新Live连续全量/立即重试/中途断连/重连回归171.105 s通过；最终归档结果见本节末。

手机已完成realme完整数据库到小米迁移（主库SHA匹配源归档），原小米1,042条GPS已备份、未并入。随后按完整归档及硬件raw集合应用精简；早先一次精简后App增至3004而硬件仍3000，确认一次性精简不足，现已实现持续策略。最新明确retain真机36.388 s通过：硬件3000、App模块3000、手机来源样本208；启用标记已持久化。手机样本包含有/无GPS，模块3000不等于整个数据库总行数；隔离记录及私人完整档案仍保留本机，不上传。

持续策略按device显式启用：仅在retain3000命令得到count=3000且idle确认后保存本地prefs。对已启用且支持0x80的设备，每次历史同步发timestamp=0全量请求；只有本会话真实END、received=3000、唯一wire timestamps=3000、前后原始8 B HISTORY_INFO完全一致、所有upsert成功及连接/会话仍属于捕获目标时，才事务删除不在该实际wire集合的模块行。删除ID每批最多500，失败/取消/切换设备全部回滚、不删除；窗口不稳定最多自动重试一次。不能按时间排序取3000，缓存行可能比设备窗口更新。启用标记仅代表策略启用，不能代替每次精简成功证据。

Room11的isPhoneSample采用INTEGER NOT NULL DEFAULT0；新手机实时行始终true，即使没有GPS。10→11无损迁移把已有GPS行标为phone，旧无GPS来源未知保守默认false；在完整归档后按真实wire集合核对，不能靠年份猜测删除。历史DAO/count/增量仅处理false且geoNull；所有phone/GPS、隔离表和其他设备受保护。严格核心数值断言曾阻止单行旧手机实时/硬件差异的错误精简，没有通过放宽断言绕过。测试覆盖来源保护、精确164缓存差集、1201条跨删除批次、SQL中段abort和session失效原子回滚。

硬件3000策略已在隔离整根SWD后的实际电池断电冷启动验证：archive_sync23.296 s、完整3000及核心/GPS/无重复/未来检查通过；再次raw19.434 s通过（3000 V3、42000 B、END、8 B INFO稳定）。此前小米1.1.0 archive_sync79.289 s通过是历史证据；当前1.1.2也已独立完成Live回归，见本节末。VDD2.765 V为10/9约18:45观测、2.875 V为10/10约00:17 VM观测，均非当前实时读数，ADC仍未校准/无万用表对照。

P3四阶段已取得擦除流程、部分写入、END后及暂停MCUboot搬运的真实电池断电恢复证据；不证明NOR WIP/NVMC写脉冲被切断。SWD仍连接时曾出现W25Q64 init_res=22，根因未定，隔离SWD重试通过不等于已定位。旧v8硬件尾部14条曾被覆盖，timestamp/传感器数值14/14均在本机完整App档案保全；新恢复函数已有真实C回归与冷启动证据。

固件资源保持App Flash151540 B、RAM23080 B（余1496 B），MCUboot31876 B（余892 B），分区不变。DEV signed候选152203 B已真机回读；生产asset152202 B未部署，DER长度可变。两镜像App payload151540 B逐字节一致，SHA256 `236323e4319f7228ce6b4856bb1736a4bede1cf58e3dfc85239678bdc943494c`；完整signed文件不能混同。生产imgtool验证通过，但生产MCUboot公钥信任尚未部署、候选产物入口为 [v1.0.9-rc.1](https://github.com/linckr/NRF52xxx-FieldTemp/releases/tag/v1.0.9-rc.1)，发布状态以页面为准；两个upstream PR #2在最近记录时OPEN，后续实时核对。

持续策略与Live验收已完成，最终归档模块3000；生产信任部署与ADC校准仍是独立待办。

### 最终Live与一致归档验收

最终真机Live回归171.105 s通过：两次连续全量各3000、立即重试、收到部分记录后断连且全部既有模块ID未删除、重连及再次同步完成3000；仍保留窗口内记录ID/核心字段和所有既有手机样本，无重复/未来时间。重连首轮观察到0条历史、随后一次自动全量重试成功；本轮已由有界重试覆盖，但首次重连延迟为Medium性能边界，根因未定位。

最终只读一致归档（2026-10-10约01:12）SQLite integrity ok：模块3000、GPS187、无GPS手机样本31（受保护手机样本共218）、active3218、quarantine22484，未来时间0、模块重复0。Live期间VM电压开始2.886 V、结束2.868 V（本轮观测，不是ADC校准证明）。App已恢复运行；手机临时USB亮屏设置已恢复原值。私人数据库、档案路径、标识与归档内容不进Git。
