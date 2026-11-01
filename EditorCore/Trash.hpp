#pragma once

// Moves a file to the Trash (Finder's on mac). Returns false where there is
// none (iOS app containers): the caller deletes instead. Objective-C++:
// define OXO_TRASH_IMPL before including this in exactly one .mm per binary.

#include <filesystem>

namespace oxo {
bool MoveToTrash(const std::filesystem::path &path);
}

#ifdef OXO_TRASH_IMPL
#import <Foundation/Foundation.h>

bool oxo::MoveToTrash(const std::filesystem::path &path) {
    @autoreleasepool {
        NSURL *url = [NSURL fileURLWithPath:[NSString stringWithUTF8String:path.c_str()]];
        return [NSFileManager.defaultManager trashItemAtURL:url resultingItemURL:nil error:nil];
    }
}
#endif
