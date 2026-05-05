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
#   make bump-major     # 1.2.3 → 2.0.0, commit, tag vX.Y.Z
#   make bump-minor     # 1.2.3 → 1.3.0, commit, tag vX.Y.Z
#   make bump-patch     # 1.2.3 → 1.2.4, commit, tag vX.Y.Z
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
PROPS       := src/ApplicationPlugin.properties

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

.PHONY: all native package debug install reinstall run clean distclean \
        bump-major bump-minor bump-patch _bump print-config help

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
	@echo "VERSION         = $(shell grep '^version=' $(PROPS) | cut -d= -f2)"

# --- Version bumping --------------------------------------------------------
# Each target reads $(PROPS), increments the requested field, writes back,
# commits the change, and creates an annotated git tag. Push with
# `git push && git push --tags` — that's also what the release workflow's
# tag trigger watches for.

bump-major:
	@$(MAKE) -s _bump TYPE=major

bump-minor:
	@$(MAKE) -s _bump TYPE=minor

bump-patch:
	@$(MAKE) -s _bump TYPE=patch

_bump:
	@if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then \
	    echo "Not inside a git repository — nothing to commit/tag." >&2; \
	    exit 1; \
	fi; \
	CUR=$$(grep '^version=' $(PROPS) | cut -d= -f2); \
	if [ -z "$$CUR" ]; then \
	    echo "version= not found in $(PROPS)" >&2; exit 1; \
	fi; \
	MAJ=$$(echo "$$CUR" | cut -d. -f1); \
	MIN=$$(echo "$$CUR" | cut -d. -f2); \
	PAT=$$(echo "$$CUR" | cut -d. -f3); \
	case "$(TYPE)" in \
	    major) NEW="$$((MAJ+1)).0.0" ;; \
	    minor) NEW="$$MAJ.$$((MIN+1)).0" ;; \
	    patch) NEW="$$MAJ.$$MIN.$$((PAT+1))" ;; \
	    *) echo "Unknown bump type: $(TYPE)" >&2; exit 1 ;; \
	esac; \
	if git rev-parse "v$$NEW" >/dev/null 2>&1; then \
	    echo "Tag v$$NEW already exists — aborting." >&2; exit 1; \
	fi; \
	sed -i.bak "s/^version=.*/version=$$NEW/" $(PROPS) && rm -f $(PROPS).bak; \
	git add $(PROPS); \
	git commit -m "chore: bump version to v$$NEW" -- $(PROPS) >/dev/null; \
	git tag -a "v$$NEW" -m "Release v$$NEW"; \
	echo; \
	echo "Bumped $$CUR -> $$NEW and created tag v$$NEW."; \
	echo "Push to publish (triggers CI release):"; \
	echo "    git push && git push --tags"

help:
	@echo "Targets:"
	@echo "  make             native + package (default, no debug logging)"
	@echo "  make native      build the JNI bridge for this platform"
	@echo "  make package     run ant to build $(SH3P)"
	@echo "  make debug       build $(SH3P) with runtime logging enabled"
	@echo "  make install     copy $(SH3P) into the local plugins folder"
	@echo "  make reinstall   clean + build + install"
	@echo "  make run         launch Sweet Home 3D (macOS only)"
	@echo "  make bump-major  bump major version, commit, tag"
	@echo "  make bump-minor  bump minor version, commit, tag"
	@echo "  make bump-patch  bump patch version, commit, tag"
	@echo "  make clean       remove build/ and dist/"
	@echo "  make distclean   clean + remove bundled native binaries"
	@echo "  make print-config  show resolved variables"
	@echo ""
	@echo "Variables:"
	@echo "  SH3D_JAR         path to SweetHome3D.jar  (current: $(SH3D_JAR))"
	@echo "  DRACO_PREFIX     install root for libdraco (auto-detected if unset)"
