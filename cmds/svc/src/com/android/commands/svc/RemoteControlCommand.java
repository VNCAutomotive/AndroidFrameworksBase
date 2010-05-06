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
 */

package com.android.commands.svc;

import android.os.RemoteControl;

public class RemoteControlCommand extends Svc.Command {
    public RemoteControlCommand() {
        super("remote");
    }

    public String shortHelp() {
        return "Control the remote control service";
    }

    public String longHelp() {
        return shortHelp() + "\n"
                + "\n"
                + "usage: svc remote add <app>\n"
                + "         Allow <app> to use remote control\n\n"
                + "       svc remote del <app>\n"
                + "         Disallow <app> from using remote control\n";
    }

    public void run(String[] args) {

        if(args.length >= 2) {
            try {
                if("add".equals(args[1]) && (args.length == 3)) {
                    RemoteControl.addAuthorisedApplication(args[2]);
                    return;
                } else if("del".equals(args[1]) && (args.length == 3)) {
                    RemoteControl.removeAuthorisedApplication(args[2]);
                    return;
                }
            } catch(SecurityException e) {
                System.err.println("Permission denied");
                return;
            }
        }

        System.err.println(longHelp());
   }
}
