from pathlib import Path
import re


def read(path):
    return Path(path).read_text(encoding='utf-8')


def write(path, value):
    Path(path).write_text(value, encoding='utf-8')


# FaceAISDK initialization can throw native/linkage errors; convert them to a runtime failure
# that the data layer can report without introducing a checked Throwable signature.
path = 'app/src/main/java/com/xingyao/card/core/FaceAiManager.java'
value = read(path)
value = value.replace('''        } catch (Throwable error) {
            initialized = false;
            update("ERROR", "FaceAISDK初始化失败：" + safeMessage(error));
            throw error;
        }''', '''        } catch (Throwable error) {
            initialized = false;
            update("ERROR", "FaceAISDK初始化失败：" + safeMessage(error));
            throw new IllegalStateException("FaceAISDK初始化失败：" + safeMessage(error), error);
        }''')
value = value.replace('''    public synchronized void restart() {''', '''    public synchronized void start() {
        if (!initialized) {
            if (appContext == null) throw new IllegalStateException("FaceAISDK尚未配置Context");
            init(appContext, listener);
        }
    }

    public synchronized void stop() {
        release();
    }

    public synchronized void restart() {''')
write(path, value)

# FaceEnrollmentController is in the UI package and reaches the Android data layer only through the
# runtime registry. It must not import the FaceAISDK adapter directly.
path = 'app/src/main/java/com/xingyao/card/FaceEnrollmentController.java'
value = read(path)
if 'import com.xingyao.card.core.DeviceRuntimeRegistry;' not in value:
    marker = 'import com.ai.face.base.addFace.AddFaceCallBack;'
    if marker not in value:
        raise RuntimeError('FaceEnrollmentController import marker not found')
    value = value.replace(marker,
                          'import com.xingyao.card.core.DeviceRuntimeRegistry;\n\n' + marker, 1)
write(path, value)

# The stage script deliberately performs broad type replacement. Normalize the sync constructor to
# one FaceAISDK adapter after the replacement, rather than retaining the former manager+cleaner pair.
path = 'app/src/main/java/com/xingyao/card/core/DeviceDataSyncManager.java'
value = read(path)
pattern = re.compile(r'''    private final NativeSettingsRepository settingsRepository;.*?\n    public synchronized JSONObject syncAll''', re.S)
replacement = '''    private final NativeSettingsRepository settingsRepository;
    private final DeviceDataRepository dataRepository;
    private final FaceAiManager faceAiManager;
    private final BackendHttpGateway httpGateway;

    public DeviceDataSyncManager(NativeSettingsRepository settingsRepository,
                                 DeviceDataRepository dataRepository,
                                 FaceAiManager faceAiManager,
                                 BackendHttpGateway httpGateway) {
        if (settingsRepository == null) throw new IllegalArgumentException("settingsRepository is required");
        if (dataRepository == null) throw new IllegalArgumentException("dataRepository is required");
        if (faceAiManager == null) throw new IllegalArgumentException("faceAiManager is required");
        if (httpGateway == null) throw new IllegalArgumentException("httpGateway is required");
        this.settingsRepository = settingsRepository;
        this.dataRepository = dataRepository;
        this.faceAiManager = faceAiManager;
        this.httpGateway = httpGateway;
    }

    public synchronized JSONObject syncAll'''
value, count = pattern.subn(replacement, value, count=1)
if count != 1:
    raise RuntimeError('Unable to normalize DeviceDataSyncManager constructor')
write(path, value)

path = 'app/src/main/java/com/xingyao/card/service/DeviceCoreService.java'
value = read(path)
value = value.replace('import com.xingyao.card.core.ArcFaceTemplateCleaner;\n', '')
value = value.replace('dataRepository, faceAiManager, faceAiManager, httpGateway',
                      'dataRepository, faceAiManager, httpGateway')
write(path, value)

print('stage1 generated sources normalized')
