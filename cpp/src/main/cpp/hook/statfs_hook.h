#ifndef ALTER_STATFS_HOOK_H
#define ALTER_STATFS_HOOK_H

// Installs the libc statvfs/statfs redirection. Call once, from a Java thread
// (JNI_OnLoad), after the JVM is up; the redirect rules themselves are read
// lazily from IOManager on every hooked call.
void InstallStatFsHooks();

#endif  // ALTER_STATFS_HOOK_H
