from pathlib import Path


def read(path):
    return Path(path).read_text(encoding='utf-8')


def write(path, value):
    Path(path).write_text(value, encoding='utf-8')


path = 'app/src/main/java/com/xingyao/card/core/DocumentedBackendService.java'
value = read(path)
value = value.replace('''    private final Context context;
    private final Transport transport;

    public DocumentedBackendService(Context context, Transport transport) {
        if (context == null) throw new IllegalArgumentException("context is required");
        if (transport == null) throw new IllegalArgumentException("transport is required");
        this.context = context.getApplicationContext();
        this.transport = transport;
    }''', '''    private final File filesDir;
    private final File cacheDir;
    private final Transport transport;

    public DocumentedBackendService(Context context, Transport transport) {
        this(requireContext(context).getFilesDir(), requireContext(context).getCacheDir(), transport);
    }

    DocumentedBackendService(File filesDir, File cacheDir, Transport transport) {
        if (filesDir == null || cacheDir == null) {
            throw new IllegalArgumentException("APP私有目录不能为空");
        }
        if (transport == null) throw new IllegalArgumentException("transport is required");
        this.filesDir = filesDir;
        this.cacheDir = cacheDir;
        this.transport = transport;
    }''')
value = value.replace('new File(context.getFilesDir(), "firmware")',
                      'new File(filesDir, "firmware")')
value = value.replace('String files = context.getFilesDir().getCanonicalPath() + File.separator;',
                      'String files = filesDir.getCanonicalPath() + File.separator;')
value = value.replace('String cache = context.getCacheDir().getCanonicalPath() + File.separator;',
                      'String cache = cacheDir.getCanonicalPath() + File.separator;')
marker = '    private static void copyOptional(JSONObject source, JSONObject target, String... fields)'
helper = '''    private static Context requireContext(Context context) {
        if (context == null) throw new IllegalArgumentException("context is required");
        Context app = context.getApplicationContext();
        return app == null ? context : app;
    }

'''
if marker not in value:
    raise RuntimeError('DocumentedBackendService helper marker not found')
value = value.replace(marker, helper + marker, 1)
write(path, value)

path = 'app/src/test/java/com/xingyao/card/core/DocumentedBackendServiceTest.java'
value = read(path)
value = value.replace('import android.test.mock.MockContext;\n\n', '')
value = value.replace('new DocumentedBackendService(new TestContext(), transport)',
                      'new DocumentedBackendService(temp("files"), temp("cache"), transport)')
value = value.replace('''new DocumentedBackendService(
                new TestContext(), new FakeTransport())''',
                      '''new DocumentedBackendService(
                temp("files"), temp("cache"), new FakeTransport())''')
value = value.replace('''    private static final class TestContext extends MockContext {
        @Override public android.content.Context getApplicationContext() { return this; }
        @Override public File getFilesDir() { return new File(System.getProperty("java.io.tmpdir"), "files"); }
        @Override public File getCacheDir() { return new File(System.getProperty("java.io.tmpdir"), "cache"); }
    }
''', '''    private static File temp(String name) {
        File value = new File(System.getProperty("java.io.tmpdir"),
                "card-prod-test-" + name);
        value.mkdirs();
        return value;
    }
''')
write(path, value)

print('stage2 JVM test injection fixed')
