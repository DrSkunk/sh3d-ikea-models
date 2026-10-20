# Build the JNI bridge to libdraco for the host platform. The output is
# placed under src/com/drskunk/sh3dikea/draco/native/<os>-<arch>/<lib>, which
# is where DracoNative.java looks for it at runtime and where ant picks it
# up when packaging the .sh3p.
#
# Run via the top-level Makefile (`make native`) or directly:
#
#   make -C native              # build for this platform
#   make -C native info         # print resolved variables and exit
#   make -C native clean        # remove this platform's binary
#
# Override the libdraco location:
#
#   make -C native DRACO_PREFIX=/some/where
#
# Requirements: a C++14 compiler and the libdraco development files
# (`apt install libdraco-dev` on Debian/Ubuntu). On macOS, build libdraco
# from source for MACOSX_DEPLOYMENT_TARGET (see README); Homebrew's bottle
# targets the host's macOS version and is rejected by `verify`.

# --- Platform detection ---------------------------------------------------

UNAME_S := $(shell uname -s)
UNAME_M := $(shell uname -m)

ifeq ($(UNAME_S),Darwin)
    OS               := macos
    LIB_PREFIX       := lib
    LIB_EXT          := dylib
    PLATFORM_INCLUDE := darwin
    LDFLAGS_OS       := -dynamiclib
else ifeq ($(UNAME_S),Linux)
    OS               := linux
    LIB_PREFIX       := lib
    LIB_EXT          := so
    PLATFORM_INCLUDE := linux
    # Static libstdc++/libgcc so the .so loads on distros and Flatpak
    # runtimes whose libstdc++ is older than the build host's. Hide the
    # statically linked libdraco symbols and refuse unresolved references.
    LDFLAGS_OS       := -shared -static-libstdc++ -static-libgcc \
                        -Wl,--exclude-libs,ALL -Wl,-z,defs -Wl,--as-needed
else ifneq (,$(filter MINGW% MSYS% CYGWIN%,$(UNAME_S)))
    OS               := windows
    LIB_PREFIX       :=
    LIB_EXT          := dll
    PLATFORM_INCLUDE := win32
    # Static-link the MinGW C++ runtime so the produced .dll doesn't need
    # libgcc_s_seh-1.dll / libstdc++-6.dll / libwinpthread-1.dll alongside it.
    LDFLAGS_OS       := -shared -static -static-libgcc -static-libstdc++ -Wl,--add-stdcall-alias
else
    $(error Unsupported OS: $(UNAME_S))
endif

ifneq (,$(filter $(UNAME_M),x86_64 amd64))
    ARCH := x86_64
else ifneq (,$(filter $(UNAME_M),arm64 aarch64))
    ARCH := arm64
else
    $(error Unsupported architecture: $(UNAME_M))
endif

# --- JDK ------------------------------------------------------------------

ifeq ($(JAVA_HOME),)
    JAVA_HOME := $(shell /usr/libexec/java_home 2>/dev/null)
endif
ifeq ($(JAVA_HOME),)
    $(error JAVA_HOME is not set and could not be inferred)
endif

# On MSYS2 / Cygwin, JAVA_HOME comes in as a Windows-style path
# (C:\hostedtoolcache\...). Translate it so the -I flags work correctly.
ifeq ($(OS),windows)
    JAVA_HOME := $(shell cygpath -m "$(JAVA_HOME)" 2>/dev/null || echo "$(JAVA_HOME)")
    DRACO_PREFIX := $(shell cygpath -m "$(DRACO_PREFIX)" 2>/dev/null || echo "$(DRACO_PREFIX)")
endif

# --- libdraco -------------------------------------------------------------

# Auto-detect Homebrew (Apple Silicon + Intel), MSYS2 mingw64, local
# install, or system path.
ifeq ($(DRACO_PREFIX),)
    DRACO_PREFIX := $(shell for p in /opt/homebrew /mingw64 /usr/local /usr; do \
        if [ -f $$p/include/draco/compression/decode.h ]; then \
            echo $$p; break; \
        fi; \
    done)
endif
ifeq ($(DRACO_PREFIX),)
    $(error libdraco not found. Install `libdraco-dev` on Debian/Ubuntu, build libdraco from source on macOS, or set DRACO_PREFIX)
endif

# libdraco must be linked statically: end users (and Flatpak/Snap sandboxes)
# don't have libdraco.so installed, so a dynamically linked bridge fails to
# load at runtime. Debian/Ubuntu put the archive under lib/<multiarch>/.
ifeq ($(OS),linux)
    MULTIARCH := $(shell m=$$($(CXX) -print-multiarch 2>/dev/null); \
        [ -n "$$m" ] || m=$$($(CXX) -dumpmachine 2>/dev/null); echo $$m)
endif
DRACO_STATIC := $(firstword $(wildcard \
    $(DRACO_PREFIX)/lib/libdraco.a \
    $(DRACO_PREFIX)/lib64/libdraco.a \
    $(if $(MULTIARCH),$(DRACO_PREFIX)/lib/$(MULTIARCH)/libdraco.a)))
ifneq ($(DRACO_STATIC),)
    DRACO_LINK := $(DRACO_STATIC)
else ifeq ($(DRACO_ALLOW_SHARED),1)
    DRACO_LINK := -L$(DRACO_PREFIX)/lib -ldraco
else
    $(error libdraco.a not found under $(DRACO_PREFIX). A dynamically linked bridge won't load on end-user machines; set DRACO_ALLOW_SHARED=1 to build one anyway for local testing)
endif

# --- Tooling --------------------------------------------------------------

CXX      ?= c++
CXXFLAGS ?= -std=c++14 -O2 -fPIC -fvisibility=hidden
INCLUDES := -I$(JAVA_HOME)/include -I$(JAVA_HOME)/include/$(PLATFORM_INCLUDE) -I$(DRACO_PREFIX)/include
LDFLAGS  := $(LDFLAGS_OS)

# On macOS, force the build to a single arch matching the host so we don't
# accidentally produce a fat binary that fails to load libdraco's other arch.
# Pin the minimum macOS version; otherwise it defaults to the build host's
# OS and the bridge refuses to load on anything older. libdraco.a must be
# built for the same (or an older) target — Homebrew's bottle isn't.
ifeq ($(OS),macos)
    MACOSX_DEPLOYMENT_TARGET ?= 11.0
    CXXFLAGS += -arch $(UNAME_M) -mmacosx-version-min=$(MACOSX_DEPLOYMENT_TARGET)
    LDFLAGS  += -arch $(UNAME_M) -mmacosx-version-min=$(MACOSX_DEPLOYMENT_TARGET)
endif

# --- Outputs --------------------------------------------------------------

LIB_NAME := $(LIB_PREFIX)dracojni.$(LIB_EXT)
DEST_DIR := ../src/com/drskunk/sh3dikea/draco/native/$(OS)-$(ARCH)
DEST     := $(DEST_DIR)/$(LIB_NAME)

# --- Targets --------------------------------------------------------------

.PHONY: all clean info verify

all: $(DEST) verify

$(DEST): src/draco_jni.cpp
	@mkdir -p $(DEST_DIR)
	$(CXX) $(CXXFLAGS) $(INCLUDES) $< $(DRACO_LINK) $(LDFLAGS) -o $@
	@echo "Built $@"
	@if command -v file >/dev/null 2>&1; then file $@; fi

# Fail if the bridge depends on anything a stock end-user system may lack.
verify: $(DEST)
ifeq ($(OS),linux)
	@deps=$$(readelf -d $(DEST) | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p'); \
	echo "Runtime deps: $$deps"; \
	bad=$$(echo "$$deps" | grep -v -E '^(libc|libm|libdl|libpthread|librt|ld-linux.*)\.so' || true); \
	if [ -n "$$bad" ] && [ "$(DRACO_ALLOW_SHARED)" != "1" ]; then \
	    echo "ERROR: $(DEST) needs non-system libraries: $$bad" >&2; exit 1; \
	fi; \
	echo "Max GLIBC symbol version: $$(objdump -T $(DEST) | grep -o 'GLIBC_[0-9.]*' | sort -t. -k2,2n -k3,3n -u | tail -1)"
else ifeq ($(OS),macos)
	@if otool -L $(DEST) | tail -n +2 | grep -i draco | grep -v dracojni; then \
	    if [ "$(DRACO_ALLOW_SHARED)" != "1" ]; then \
	        echo "ERROR: $(DEST) links libdraco dynamically" >&2; exit 1; \
	    fi; \
	fi
	@minos=$$(otool -l $(DEST) | awk '/minos/ {print $$2; exit}'); \
	echo "Minimum macOS: $$minos"; \
	if [ "$$minos" != "$(MACOSX_DEPLOYMENT_TARGET)" ] && [ "$(DRACO_ALLOW_SHARED)" != "1" ]; then \
	    echo "ERROR: expected minimum macOS $(MACOSX_DEPLOYMENT_TARGET), got $$minos" >&2; exit 1; \
	fi
	@if [ -n "$(DRACO_STATIC)" ] && [ "$(DRACO_ALLOW_SHARED)" != "1" ]; then \
	    dmin=$$(otool -l $(DRACO_STATIC) | awk '/minos/ {print $$2}' | sort -t. -k1,1n -k2,2n | tail -1); \
	    hi=$$(printf '%s\n%s\n' "$$dmin" "$(MACOSX_DEPLOYMENT_TARGET)" | sort -t. -k1,1n -k2,2n | tail -1); \
	    if [ "$$hi" != "$(MACOSX_DEPLOYMENT_TARGET)" ]; then \
	        echo "ERROR: $(DRACO_STATIC) targets macOS $$dmin, newer than $(MACOSX_DEPLOYMENT_TARGET)." >&2; \
	        echo "Build libdraco with -DCMAKE_OSX_DEPLOYMENT_TARGET=$(MACOSX_DEPLOYMENT_TARGET) and pass DRACO_PREFIX." >&2; \
	        rm -f $(DEST); exit 1; \
	    fi; \
	fi
else
	@deps=$$(objdump -p $(DEST) | sed -n 's/.*DLL Name: //p'); \
	echo "Runtime deps: $$deps"; \
	bad=$$(echo "$$deps" | grep -v -i -E '^(kernel32|msvcrt|user32|advapi32|ws2_32|api-ms-win-.*)\.dll$$' || true); \
	if [ -n "$$bad" ] && [ "$(DRACO_ALLOW_SHARED)" != "1" ]; then \
	    echo "ERROR: $(DEST) needs non-system DLLs: $$bad" >&2; exit 1; \
	fi
endif

clean:
	rm -f $(DEST)

info:
	@echo "OS              = $(OS)"
	@echo "ARCH            = $(ARCH)"
	@echo "JAVA_HOME       = $(JAVA_HOME)"
	@echo "DRACO_PREFIX    = $(DRACO_PREFIX)"
	@echo "MULTIARCH       = $(MULTIARCH)"
	@echo "DRACO_LINK      = $(DRACO_LINK)"
	@echo "CXX             = $(CXX)"
	@echo "CXXFLAGS        = $(CXXFLAGS)"
	@echo "DEST            = $(DEST)"
