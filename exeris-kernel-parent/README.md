# Exeris Kernel Parent

**Module:** `eu.exeris:exeris-kernel-parent`  
**Role:** Global Plugin & Profile Configuration

## Overview
The Parent POM is the "glue" of the repository. It inherits from the Root POM and provides a standardized build lifecycle for all kernel sub-modules.

## 🚀 Key Features
- **Strict Quality Gates:** Automatically binds Checkstyle and PMD verification to the `validate` and `verify` phases.
- **Java 26 Preview:** Configures the `maven-compiler-plugin` to enable preview features (JEP 525, 526, 401) across all children.
- **Native Access:** Configures Surefire to allow native access for modules using Panama FFM.