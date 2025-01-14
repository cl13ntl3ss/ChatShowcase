import * as Stomp from 'stompjs';
import * as SockJS from 'sockjs-client';
import { ChatbotComponent } from './chatbot/chatbot.component';

export class WebSocketAPI {
    webSocketEndPoint: string = 'http://localhost:8080/gs-guide-websocket';
    topic: string = "/user/topic/weather";
    stompClient: any;
    chatComponent: ChatbotComponent;
    constructor(chatbotComponent: ChatbotComponent){
        this.chatComponent = chatbotComponent;
    }
    _connect() {
        console.log("Initialize WebSocket Connection");
        let ws = new SockJS(this.webSocketEndPoint);
        this.stompClient = Stomp.over(ws);
        const _this = this;
        _this.stompClient.connect({}, function (frame: any) {
            _this.stompClient.subscribe(_this.topic, function (sdkEvent: any) {
                _this.onMessageReceived(sdkEvent);
            });
            _this.stompClient.subscribe("/user/topic/currentLoc", function (sdkEvent: any) {
                _this.onLocationReceived(sdkEvent);
            });
            _this.stompClient.subscribe("/user/topic/icon", function (sdkEvent: any) {
                _this.onIconReceived(sdkEvent);
            });
        }, this.errorCallBack);
    };

    _disconnect() {
        if (this.stompClient !== null) {
            this.stompClient.disconnect();
        }
        console.log("Disconnected");
    }

    // on error, schedule a reconnection attempt
    errorCallBack(error: any) {
        console.log("errorCallBack -> " + error)
        setTimeout(() => {
            this._connect();
        }, 5000);
    }

 /**
  * Send message to sever via web socket
  * @param {*} message
  */
    _send(message: any) {
        console.log(message);
        console.log("calling logout api via web socket");
        this.stompClient.send("/app/inquiry", {}, JSON.stringify(message));
    }

    _sendLocation(lon: number, lat: number) {
        console.log("calling logout api via web socket");
        this.stompClient.send("/app/lat", {}, JSON.stringify(lat));
        this.stompClient.send("/app/lon", {}, JSON.stringify(lon));
    }

    _sendIconRequest(): void {
        console.log("calling logout api via web socket");
        this.stompClient.send("/app/icon",{});
    }

    _sendLanguageChange(language: string): void {
        console.log("Language has been changed!");
        this.stompClient.send("/app/lang", {}, language);
        this._sendLocation(this.chatComponent.currentLon,this.chatComponent.currentLat);
    }

    onMessageReceived(message: any) {
        this._sendIconRequest();
        console.log("Message Recieved from Server :: " + message);
        this.chatComponent.handleMessage(JSON.parse(message.body).content);
    }

    onLocationReceived(location: any) {
        console.log("Location Recieved from Server :: " + JSON.parse(location.body).content);
        this.chatComponent.updateCurrentLocation(JSON.parse(location.body).content);
    }

    onIconReceived(icon: any){
        console.log("Icon Recieved from Server :: " + JSON.parse(icon.body).content);
        if(JSON.parse(icon.body).content != null){this.chatComponent.addWeatherImage(JSON.parse(icon.body).content)}
    }
}
