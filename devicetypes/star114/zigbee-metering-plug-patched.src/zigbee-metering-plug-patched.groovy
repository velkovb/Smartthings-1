/**
 *  Copyright 2019 SmartThings / star114
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
import physicalgraph.zigbee.zcl.DataType

metadata {
    definition (name: "Zigbee Metering Plug (Patched)", namespace: "star114", author: "SmartThings/star114", ocfDeviceType: "oic.d.smartplug", mnmn: "SmartThings",  vid: "generic-switch-power-energy") {
        capability "Energy Meter"
        capability "Power Meter"
        capability "Actuator"
        capability "Switch"
        capability "Refresh"
        capability "Health Check"
        capability "Sensor"
        capability "Configuration"

        command "reset"
        
        // DAWON
        fingerprint endpointId: "01", profileId: "0104", deviceId: "0051", inClusters: "0000, 0002, 0003, 0004, 0006, 0019, 0702, 0B04, 0008, 0009", outClusters: "0000, 0002, 0003, 0004, 0006, 0019, 0702, 0B04, 0008, 0009", manufacturer: "DAWON_DNS", model: "PM-B530-ZB", deviceJoinName: "DAWON Zigbee Smart Plug 16A"
        fingerprint profileId: "0104", inClusters: "0000, 0002, 0003, 0006, 0702, 0B04", outClusters: "0003, 0019", manufacturer: "DAWON_DNS", model: "PM-B540-ZB",  deviceJoinName: "DAWON Zigbee Smart Plug 16A (New)"
    }

    tiles(scale: 2){
        multiAttributeTile(name:"switch", type: "generic", width: 6, height: 4, canChangeIcon: true) {
            tileAttribute("device.switch", key: "PRIMARY_CONTROL") {
                attributeState("on", label: '${name}', action: "switch.off", icon: "st.switches.switch.on", backgroundColor: "#00a0dc")
                attributeState("off", label: '${name}', action: "switch.on", icon: "st.switches.switch.off", backgroundColor: "#ffffff")
            }
        }
        valueTile("power", "device.power", decoration: "flat", width: 2, height: 2) {
            state "default", label:'${currentValue} W'
        }
        valueTile("energy", "device.energy", decoration: "flat", width: 2, height: 2) {
            state "default", label:'${currentValue} kWh'
        }
        standardTile("refresh", "device.power", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
            state "default", label:'', action:"refresh.refresh", icon:"st.secondary.refresh"
        }
        standardTile("reset", "device.energy", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
            state "default", label:'reset kWh', action:"reset"
        }

        main(["switch"])
        details(["switch","power","energy","refresh","reset"])
    }
}

def getATTRIBUTE_READING_INFO_SET() { 0x0000 }
def getATTRIBUTE_HISTORICAL_CONSUMPTION() { 0x0400 }

def parse(String description) {
    log.debug "description is $description"
    def event = zigbee.getEvent(description)
    def descMap = zigbee.parseDescriptionAsMap(description)

    if (event) {
        log.info "event enter:$event"
        if (event.name == "switch" && !descMap.isClusterSpecific && descMap.commandInt == 0x0B) {
            log.info "Ignoring default response with desc map: $descMap"
            return [:]
        } else if (event.name== "power") {
            event.value = event.value/getPowerDiv()
            event.unit = "W"
        } else if (event.name== "energy") {
            event.value = event.value/getEnergyDiv()
            event.unit = "kWh"
        }
        log.info "event outer:$event"
        sendEvent(event)
    } else {
        List result = []
        log.debug "Desc Map: $descMap"

        List attrData = [[clusterInt: descMap.clusterInt ,attrInt: descMap.attrInt, value: descMap.value]]
        descMap.additionalAttrs.each {
            attrData << [clusterInt: descMap.clusterInt, attrInt: it.attrInt, value: it.value]
        }

        attrData.each {
            def map = [:]
            if (it.value && it.clusterInt == zigbee.SIMPLE_METERING_CLUSTER && it.attrInt == ATTRIBUTE_HISTORICAL_CONSUMPTION) {
                log.debug "power"
                map.name = "power"
                map.value = zigbee.convertHexToInt(it.value)/getPowerDiv()
                map.unit = "W"
            }
            else if (it.value && it.clusterInt == zigbee.SIMPLE_METERING_CLUSTER && it.attrInt == ATTRIBUTE_READING_INFO_SET) {
                log.debug "energy"
                map.name = "energy"
                map.value = zigbee.convertHexToInt(it.value)/getEnergyDiv()
                map.unit = "kWh"
            }

            if (map) {
                result << createEvent(map)
                log.debug "Parse returned $map"
            }
        }
        return result
    }
}

def off() {
    def cmds = zigbee.off()
    return cmds
}


def on() {
    def cmds = zigbee.on()
    return cmds
}

/**
 * PING is used by Device-Watch in attempt to reach the Device
 * */
def ping() {
    return refresh()
}

def refresh() {
    log.debug "refresh()"
    zigbee.onOffRefresh() +
    zigbee.electricMeasurementPowerRefresh() +
    zigbee.readAttribute(zigbee.SIMPLE_METERING_CLUSTER, ATTRIBUTE_READING_INFO_SET)
}

def reset() {
    log.debug "reset()"
    zigbee.writeAttribute(zigbee.SIMPLE_METERING_CLUSTER, 0x0099, DataType.UINT8, 00)
}

def configure() {
    // this device will send instantaneous demand and current summation delivered every 1 minute
    sendEvent(name: "checkInterval", value: 2 * 60 + 10 * 60, displayed: false, data: [protocol: "zigbee", hubHardwareId: device.hub.hardwareID])
    log.debug "Configuring Reporting"
    return refresh() +
           zigbee.onOffConfig() +
           zigbee.configureReporting(zigbee.SIMPLE_METERING_CLUSTER, ATTRIBUTE_READING_INFO_SET, DataType.UINT48, 1, 600, 1) +
           zigbee.electricMeasurementPowerConfig(1, 600, 1) +
           zigbee.simpleMeteringPowerConfig()
}

private int getPowerDiv() {
    return 1
}

private int getEnergyDiv() {
    return 1000
}