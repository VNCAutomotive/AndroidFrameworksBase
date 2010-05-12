/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *
 * This is the 'screenshot' command, which asks the SurfaceFlinger for
 * an image of the screen, and writes it to standard output.  The
 * 'adbd' daemon uses this command to capture screen images if
 * possible, because reading the frame buffer directly is not
 * reliable.
 */

#include <sys/mman.h>

#include <binder/Parcel.h>
#include <binder/IServiceManager.h>
#include <ui/DisplayInfo.h>
#include <ui/ISurfaceComposer.h>
#include <ui/SurfaceComposerClient.h>
#include <cutils/log.h>
#include <cutils/ashmem.h>

using namespace android;

/* This version number defines the format of the fbinfo struct.
   It must match versioning in ddms where this data is consumed. */
#define DDMS_RAWIMAGE_VERSION 1
struct fbinfo {
    unsigned int version;
    unsigned int bpp;
    unsigned int size;
    unsigned int width;
    unsigned int height;
    unsigned int red_offset;
    unsigned int red_length;
    unsigned int blue_offset;
    unsigned int blue_length;
    unsigned int green_offset;
    unsigned int green_length;
    unsigned int alpha_offset;
    unsigned int alpha_length;
} __attribute__((packed));

/* convenience wrapper around write that will retry on EINTR and/or
** short write.  Returns 0 on success, -1 on error or EOF.
*/
int writex(int fd, const void *ptr, size_t len)
{
    char *p = (char*) ptr;
    int r;

    while(len > 0) {
        r = write(fd, p, len);
        if(r > 0) {
            len -= r;
            p += r;
        } else {
            if((r < 0) && (errno == EINTR)) continue;
            return -1;
        }
    }

    return 0;
}

status_t write_data(int fd, DisplayInfo *info, const uint8_t *data, size_t len)
{
    struct fbinfo fbinfo;
    size_t linesize;
    uint32_t y;

    fbinfo.version = DDMS_RAWIMAGE_VERSION;
    fbinfo.bpp = info->pixelFormatInfo.bytesPerPixel * 8;
    fbinfo.size = info->w * info->h * info->pixelFormatInfo.bytesPerPixel;
    fbinfo.width = info->w;
    fbinfo.height = info->h;

    switch(info->pixelFormatInfo.format)
    {
    case PIXEL_FORMAT_RGBA_8888:
        fbinfo.red_offset   =  0; fbinfo.red_length   = 8;
        fbinfo.green_offset =  8; fbinfo.green_length = 8;
        fbinfo.blue_offset  = 16; fbinfo.blue_length  = 8;
        break;

    case PIXEL_FORMAT_RGB_565:
        fbinfo.red_offset   = 11; fbinfo.red_length   = 5;
        fbinfo.green_offset =  5; fbinfo.green_length = 6;
        fbinfo.blue_offset  =  0; fbinfo.blue_length  = 5;
        break;

    default:
        LOGE("Unknown pixel format: %d", info->pixelFormatInfo.format);
        return BAD_VALUE;
    }

    if(writex(fd, &fbinfo, sizeof(fbinfo))) {
        LOGE("write(): %s", strerror(errno));
        return -errno;
    }

    /* The screenshot we get from the SurfaceFlinger is in OpenGL row
     * order, ie the bottom row first. So we have to send the scan
     * lines to our client in the reverse order. */

    linesize = info->pixelFormatInfo.bytesPerPixel * info->w;
    data += linesize * info->h;

    for(y=0; y<info->h; y++) {
        data -= linesize;
        if(writex(fd, data, linesize)) {
            LOGE("write(): %s", strerror(errno));
            return -errno;
        }
    }

    return NO_ERROR;
}

int main(void)
{
    DisplayInfo info;
    int fb_fd;
    status_t err;
    int dpy = 0;
    size_t fb_size;
    void *data;
    SurfaceComposerClient srfCmp;

    err = srfCmp.getDisplayInfo(dpy, &info);
    if(err) {
        LOGE("getDisplayInfo: %d", err);
        return -1;
    }

    if(info.orientation & ISurfaceComposer::eOrientationSwapMask) {
        /* The width and height we just retrieved have been corrected
         * for the screen's orientation. However the frame buffer is
         * not, so we need to undo the correction here. */
        uint32_t tmp = info.w;
        info.w = info.h;
        info.h = tmp;
    }

    fb_size = info.w * info.h * info.pixelFormatInfo.bytesPerPixel;

    fb_fd = ashmem_create_region(NULL, fb_size);
    if(fb_fd < 0) {
        LOGE("ashmem_create_region: %s", strerror(errno));
        return -errno;
    }

    /* Looks like we can't use read() on an ashmem region - we have to
     * mmap() it to read the contents */
    data = mmap(0, fb_size, PROT_READ, MAP_SHARED, fb_fd, 0);
    if(data == MAP_FAILED) {
        LOGE("mmap failed: %s", strerror(errno));
        return -errno;
    }

    err = srfCmp.grabScreen(dpy, fb_fd);
    if(err) {
        LOGE("grabScreen: %d", err);
        return -1;
    }

    err = write_data(STDOUT_FILENO, &info, (const uint8_t *)data, fb_size);
    if(err) {
        LOGE("write_data: %d", err);
        return -1;
    }

    if(munmap(data, fb_size) < 0)
    {
        LOGE("munmap(): %s", strerror(errno));
    }

    close(fb_fd);
    return 0;
}
