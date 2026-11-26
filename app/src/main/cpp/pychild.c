#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef void (*py_init_t)(void);
typedef int (*py_run_file_t)(FILE *, const char *);
typedef void (*py_finalize_t)(void);

static void die(const char *msg) {
    fprintf(stderr, "pychild: %s\n", msg);
    exit(70);
}

int main(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: %s <python-home> <child-script>\n", argv[0]);
        return 64;
    }
    const char *pyhome = argv[1];
    const char *script = argv[2];

    setenv("PYTHONHOME", pyhome, 1);
    if (!getenv("PYTHONPATH") || !getenv("PYTHONPATH")[0]) {
        char path[4096];
        snprintf(path, sizeof(path), "%s/lib/python3.11:%s/app", pyhome, pyhome);
        setenv("PYTHONPATH", path, 1);
    }

    void *lib = dlopen("libpython3.11.so", RTLD_NOW | RTLD_GLOBAL);
    if (!lib) {
        char msg[2048];
        snprintf(msg, sizeof(msg), "dlopen libpython3.11.so failed: %s", dlerror());
        die(msg);
    }
    py_init_t init = (py_init_t)dlsym(lib, "Py_Initialize");
    py_run_file_t run_file = (py_run_file_t)dlsym(lib, "PyRun_SimpleFile");
    py_finalize_t fin = (py_finalize_t)dlsym(lib, "Py_Finalize");
    if (!init || !run_file) die("missing Python symbols");

    setvbuf(stdout, NULL, _IONBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);

    init();
    FILE *fp = fopen(script, "r");
    if (!fp) {
        char msg[2048];
        snprintf(msg, sizeof(msg), "cannot open child script: %s", script);
        die(msg);
    }
    int rc = run_file(fp, script);
    fclose(fp);
    if (fin) fin();
    return rc == 0 ? 0 : 71;
}
