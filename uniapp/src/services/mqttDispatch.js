export function assertMqttDispatchAccepted(result, cmd = 'mqtt.send') {
  if (result === true || result?.sent === true) return result
  const error = new Error(`${cmd} 未确认消息已发送`)
  error.code = 'MQTT_SEND_NOT_ACCEPTED'
  throw error
}
