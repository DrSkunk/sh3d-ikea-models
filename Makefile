# Top-level convenience wrapper around `native/Makefile` and `ant`. The real
# build logic lives in those files; this Makefile gives a single uniform
# entry point so contributors don't have to remember which command does what.
#
# Common usage:
#   make                # build native lib + package the .sh3p
#   make native         # rebuild only the JNI bridge
#   make package        # only re-run ant (no native rebuild)
#   make debug          # like `make`, but with runtime logging on
#   make install        # copy the .sh3p into the local plugins folder
#   make reinstall      # clean + build + install
#   make run            # launch Sweet Home 3D (macOS only)
#   make clean          # remove build/ and dist/
#   make distclean      # clean + remove bundled native binaries
#   make print-config   # show resolved variables
#   make help           # list targets
#
# Override paths on the command line as needed:
#   make SH3D_JAR=/path/to/SweetHome3D.jar
#   make native DRACO_PREFIX=/opt/local

# --- Configuration -----------------------------------------------------------

PLUGIN_NAME := IkeaBrowser
SH3P        := dist/$(PLUGIN_NAME).sh3p

UNAME       := $(shell uname -s)

# Prefer lib/SweetHome3D.jar if it's been dropped into the project; otherwise
# fall back to a platform-specific default. Override with `make SH3D_JAR=...`.
LOCAL_SH3D_JAR := lib/SweetHome3D.jar

ifeq ($(UNAME),Darwin)
# Newer Sweet Home 3D bundles ship jars under Contents/app/. Older bundles
# used Contents/Java/. Prefer the new location, fall back to the old.
DEFAULT_SH3D_JAR := $(firstword $(wildcard \
    /Applications/Sweet\ Home\ 3D.app/Contents/app/SweetHome3D.jar \
    /Applications/Sweet\ Home\ 3D.app/Contents/Java/SweetHome3D.jar))
SH3D_PLUGIN_DIR  ?= $(HOME)/Library/Application Support/eTeks/Sweet Home 3D/plugins
SH3D_APP         ?= /Applications/Sweet Home 3D.app
else ifeq ($(UNAME),Linux)
DEFAULT_SH3D_JAR := /usr/share/sweethome3d/SweetHome3D.jar
SH3D_PLUGIN_DIR  ?= $(HOME)/.eteks/sweethome3d/plugins
else
DEFAULT_SH3D_JAR := $(LOCAL_SH3D_JAR)
SH3D_PLUGIN_DIR  ?= $(APPDATA)/eTeks/Sweet Home 3D/plugins
endif

ifneq ($(wildcard $(LOCAL_SH3D_JAR)),)
SH3D_JAR ?= $(LOCAL_SH3D_JAR)
else
SH3D_JAR ?= $(DEFAULT_SH3D_JAR)
endif

# DRACO_PREFIX is consumed by native/Makefile; export so it's inherited.
export DRACO_PREFIX

NATIVE_DIR := src/com/drskunk/sh3dikea/draco/native
ANT        ?= ant

# --- Targets -----------------------------------------------------------------

.PHONY: all native package debug install reinstall run clean distclean print-config help

all: native package

native:
	@$(MAKE) -C native

# Always run ant — it does its own incremental compilation, and Make can't
# track the per-file Java/properties/native dependency graph.
package:
	@if [ -z "$(SH3D_JAR)" ] || [ ! -f "$(SH3D_JAR)" ]; then \
	    echo "SweetHome3D.jar not found at: $(SH3D_JAR)"; \
	    echo "Set SH3D_JAR=/path/to/SweetHome3D.jar or drop the jar into lib/"; \
	    exit 1; \
	fi
	$(ANT) -Dsh3d.jar="$(SH3D_JAR)" package

# Debug build — same outputs as `make`, but with IkeaLog enabled. Logs
# land in <SH3D-app-dir>/IkeaBrowser/debug.log; release builds skip the
# logging entirely.
debug: native
	@if [ -z "$(SH3D_JAR)" ] || [ ! -f "$(SH3D_JAR)" ]; then \
	    echo "SweetHome3D.jar not found at: $(SH3D_JAR)"; \
	    echo "Set SH3D_JAR=/path/to/SweetHome3D.jar or drop the jar into lib/"; \
	    exit 1; \
	fi
	$(ANT) -Dsh3d.jar="$(SH3D_JAR)" -Ddebug=true clean package

install: package
	@if [ ! -d "$(SH3D_PLUGIN_DIR)" ]; then \
	  echo "Creating $(SH3D_PLUGIN_DIR)"; \
	  mkdir -p "$(SH3D_PLUGIN_DIR)"; \
	fi
	cp "$(SH3P)" "$(SH3D_PLUGIN_DIR)/"
	@echo "Installed to $(SH3D_PLUGIN_DIR)/$(PLUGIN_NAME).sh3p"
	@echo "Restart Sweet Home 3D to pick up the new build."

reinstall: clean all install

run:
ifeq ($(UNAME),Darwin)
	open "$(SH3D_APP)"
else
	@echo "make run is only wired up for macOS; launch Sweet Home 3D manually."
endif

clean:
	$(ANT) clean
	@rm -rf build dist
	@$(MAKE) -C native clean

# distclean also wipes the committed native binaries — use this if you want
# to verify a from-scratch build on every supported platform.
distclean: clean
	@rm -rf $(NATIVE_DIR)/*

print-config:
	@echo "PLUGIN_NAME     = $(PLUGIN_NAME)"
	@echo "SH3P            = $(SH3P)"
	@echo "SH3D_JAR        = $(SH3D_JAR)"
	@echo "SH3D_PLUGIN_DIR = $(SH3D_PLUGIN_DIR)"
	@echo "DRACO_PREFIX    = $(DRACO_PREFIX)"

help:
	@echo "Targets:"
	@echo "  make             native + package (default, no debug logging)"
	@echo "  make native      build the JNI bridge for this platform"
	@echo "  make package     run ant to build $(SH3P)"
	@echo "  make debug       build $(SH3P) with runtime logging enabled"
	@echo "  make install     copy $(SH3P) into the local plugins folder"
	@echo "  make reinstall   clean + build + install"
	@echo "  make run         launch Sweet Home 3D (macOS only)"
	@echo "  make clean       remove build/ and dist/"
	@echo "  make distclean   clean + remove bundled native binaries"
	@echo "  make print-config  show resolved variables"
	@echo ""
	@echo "Variables:"
	@echo "  SH3D_JAR         path to SweetHome3D.jar  (current: $(SH3D_JAR))"
	@echo "  DRACO_PREFIX     install root for libdraco (auto-detected if unset)"
