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
# (`brew install draco` on macOS, `apt install libdraco-dev` on Debian/Ubuntu).

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
    LDFLAGS_OS       := -shared -Wl,-rpath,$$ORIGIN
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
    $(error libdraco not found. Install with `brew install draco` / `apt install libdraco-dev`, or set DRACO_PREFIX)
endif

# Static library is preferred so the .sh3p doesn't need libdraco at runtime.
DRACO_STATIC := $(DRACO_PREFIX)/lib/libdraco.a
ifneq ($(wildcard $(DRACO_STATIC)),)
    DRACO_LINK := $(DRACO_STATIC)
else
    DRACO_LINK := -L$(DRACO_PREFIX)/lib -ldraco
endif

# --- Tooling --------------------------------------------------------------

CXX      ?= c++
CXXFLAGS ?= -std=c++14 -O2 -fPIC -fvisibility=hidden
INCLUDES := -I$(JAVA_HOME)/include -I$(JAVA_HOME)/include/$(PLATFORM_INCLUDE) -I$(DRACO_PREFIX)/include
LDFLAGS  := $(LDFLAGS_OS)

# On macOS, force the build to a single arch matching the host so we don't
# accidentally produce a fat binary that fails to load libdraco's other arch.
ifeq ($(OS),macos)
    CXXFLAGS += -arch $(UNAME_M)
    LDFLAGS  += -arch $(UNAME_M)
endif

# --- Outputs --------------------------------------------------------------

LIB_NAME := $(LIB_PREFIX)dracojni.$(LIB_EXT)
DEST_DIR := ../src/com/drskunk/sh3dikea/draco/native/$(OS)-$(ARCH)
DEST     := $(DEST_DIR)/$(LIB_NAME)

# --- Targets --------------------------------------------------------------

.PHONY: all clean info

all: $(DEST)

$(DEST): src/draco_jni.cpp
	@mkdir -p $(DEST_DIR)
	$(CXX) $(CXXFLAGS) $(INCLUDES) $< $(DRACO_LINK) $(LDFLAGS) -o $@
	@echo "Built $@"
	@if command -v file >/dev/null 2>&1; then file $@; fi

clean:
	rm -f $(DEST)

info:
	@echo "OS              = $(OS)"
	@echo "ARCH            = $(ARCH)"
	@echo "JAVA_HOME       = $(JAVA_HOME)"
	@echo "DRACO_PREFIX    = $(DRACO_PREFIX)"
	@echo "DRACO_LINK      = $(DRACO_LINK)"
	@echo "CXX             = $(CXX)"
	@echo "CXXFLAGS        = $(CXXFLAGS)"
	@echo "DEST            = $(DEST)"
