#include "CBrootLiteBridge.h"

#include <mach/mach.h>

bl_process_memory_snapshot bl_get_process_memory(void) {
    bl_process_memory_snapshot snap = { 0.0, 0.0 };

    struct mach_task_basic_info basic_info;
    mach_msg_type_number_t basic_count = MACH_TASK_BASIC_INFO_COUNT;
    if (task_info(
            mach_task_self(),
            MACH_TASK_BASIC_INFO,
            (task_info_t)&basic_info,
            &basic_count
        ) == KERN_SUCCESS) {
        snap.resident_mb = (double)basic_info.resident_size / (1024.0 * 1024.0);
    }

    task_vm_info_data_t vm_info;
    mach_msg_type_number_t vm_count = TASK_VM_INFO_COUNT;
    if (task_info(
            mach_task_self(),
            TASK_VM_INFO,
            (task_info_t)&vm_info,
            &vm_count
        ) == KERN_SUCCESS) {
        snap.phys_footprint_mb = (double)vm_info.phys_footprint / (1024.0 * 1024.0);
    } else {
        snap.phys_footprint_mb = snap.resident_mb;
    }

    return snap;
}
