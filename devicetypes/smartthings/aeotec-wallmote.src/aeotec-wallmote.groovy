/**
 *  Copyright 2019 SmartThings
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */

import groovy.json.JsonOutput

metadata {
    definition (name: "Aeotec Wallmote", namespace: "smartthings", author: "SmartThings", ocfDeviceType: "x.com.st.d.remotecontroller", mcdSync: true) {
        capability "Actuator"
        capability "Button"
        capability "Battery"
        capability "Holdable Button"
        capability "Configuration"
        capability "Sensor"
        capability "Health Check"

        fingerprint mfr: "0086", model: "0082", deviceJoinName: "Aeotec Wallmote Quad", mnmn: "SmartThings", vid: "generic-4-button"
        fingerprint mfr: "0086", model: "0081", deviceJoinName: "Aeotec Wallmote", mnmn: "SmartThings", vid: "generic-2-button"
    }

    tiles(scale: 2) {
        multiAttributeTile(name: "rich-control", type: "generic", width: 6, height: 4, canChangeIcon: true) {
            tileAttribute("device.button", key: "PRIMARY_CONTROL") {
                attributeState "default", label: ' ', action: "", icon: "st.unknown.zwave.remote-controller", backgroundColor: "#ffffff"
            }
        }
        valueTile("battery", "device.battery", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
            state "battery", label:'${currentValue}% battery', unit:""
        }

        main("rich-control")
        details(["rich-control", childDeviceTiles("endpoints"), "battery"])
    }
}

def getNumberOfButtons() {
    def modelToButtons = ["0082" : 4, "0081": 2]
    return modelToButtons[zwaveInfo.model] ?: 1
}

def installed() {
    sendEvent(name: "numberOfButtons", value: numberOfButtons)
    sendEvent(name: "supportedButtonValues", value: ["pushed", "held"])
    sendEvent(name: "button", value: "pushed", data: [buttonNumber: 1])
}

def updated() {
    createChildDevices()
    if (device.label != state.oldLabel) {
        childDevices.each {
            def segs = it.deviceNetworkId.split(":")
            def newLabel = "${device.displayName} button ${segs[-1]}"
            it.setLabel(newLabel)
        }
        state.oldLabel = device.label
    }
}

def configure() {
    sendEvent(name: "DeviceWatch-Enroll", value: [protocol: "zwave", scheme:"untracked"].encodeAsJson(), displayed: false)
    createChildDevices()
    response([
            secure(zwave.batteryV1.batteryGet()),
            "delay 2000",
            secure(zwave.wakeUpV2.wakeUpNoMoreInformation())
    ])
}

def parse(String description) {
    def results = []
    if (description.startsWith("Err")) {
        results = createEvent(descriptionText:description, displayed:true)
    } else {
        def cmd = zwave.parse(description)
        if (cmd) results += zwaveEvent(cmd)
        if (!results) results = [ descriptionText: cmd, displayed: false ]
    }
    return results
}

def zwaveEvent(physicalgraph.zwave.commands.centralscenev1.CentralSceneNotification cmd) {
    def button = cmd.sceneNumber
    def value
    // 0 = pushed, 1 = released, 2 = held down
    if (cmd.keyAttributes != 2) {
        value = cmd.keyAttributes == 1 ? "held" : "pushed"
    } else {
        // we can't do anything with the held down event yet
        return []
    }
    def child = getChildDevice(button)
    child?.sendEvent(name: "button", value: value, data: [buttonNumber: button], descriptionText: "$child.displayName was $value", isStateChange: true)
    createEvent(name: "button", value: value, data: [buttonNumber: button], descriptionText: "$device.displayName button $button was $value", isStateChange: true)
}

def zwaveEvent(physicalgraph.zwave.commands.securityv1.SecurityMessageEncapsulation cmd) {
    def encapsulatedCommand = cmd.encapsulatedCommand()
    if (encapsulatedCommand) {
        return zwaveEvent(encapsulatedCommand)
    } else {
        log.warn "Unable to extract encapsulated cmd from $cmd"
        createEvent(descriptionText: cmd.toString())
    }
}

def zwaveEvent(physicalgraph.zwave.Command cmd) {
    def linkText = device.label ?: device.name
    [linkText: linkText, descriptionText: "$linkText: $cmd", displayed: false]
}


def zwaveEvent(physicalgraph.zwave.commands.wakeupv2.WakeUpNotification cmd) {
    def results = []
    results += createEvent(descriptionText: "$device.displayName woke up", isStateChange: false)
    results += response([
            secure(zwave.batteryV1.batteryGet()),
            "delay 2000",
            secure(zwave.wakeUpV2.wakeUpNoMoreInformation())
    ])
    results
}

def zwaveEvent(physicalgraph.zwave.commands.batteryv1.BatteryReport cmd) {
    def map = [ name: "battery", unit: "%", isStateChange: true ]
    if (cmd.batteryLevel == 0xFF) {
        map.value = 1
        map.descriptionText = "$device.displayName battery is low!"
    } else {
        map.value = cmd.batteryLevel
    }
    createEvent(map)
}

def createChildDevices() {
    if (!childDevices) {
        state.oldLabel = device.label
        def child
        for (i in 1..numberOfButtons) {
            child = addChildDevice("Child Button", "${device.deviceNetworkId}:${i}", device.hubId,
                    [completedSetup: true, label: "${device.displayName} button ${i}",
                     isComponent: true, componentName: "button$i", componentLabel: "Button $i"])
            child.sendEvent(name: "button", value: "pushed", data: [buttonNumber: i], descriptionText: "$child.displayName was pushed", isStateChange: true)
        }
    }
}

def secure(cmd) {
    if (zwaveInfo.zw.endsWith("s")) {
        zwave.securityV1.securityMessageEncapsulation().encapsulate(cmd).format()
    } else {
        cmd.format()
    }
}

def getChildDevice(button) {
    String childDni = "${device.deviceNetworkId}:${button}"
    def child = childDevices.find{it.deviceNetworkId == childDni}
    if (!child) {
        log.error "Child device $childDni not found"
    }
    return child
}