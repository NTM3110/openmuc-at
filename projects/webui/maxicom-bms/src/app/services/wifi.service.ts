// src/app/services/wifi.service.ts
import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import { of } from 'rxjs';

/**
 * Represents a WiFi network from the scan results.
 */
export interface WifiNetwork {
    ssid: string;
    bssid: string;
    mode: string;
    channel: number;
    rate: string;
    signal: number;
    bars: string;
    security: string;
    inUse: boolean;
}

/**
 * Response from WiFi connection attempt.
 */
export interface WifiConnectionResponse {
    success: boolean;
    message?: string;
    error?: string;
}

@Injectable({
    providedIn: 'root',
})
export class WifiService {
    private wifiApiUrl = '/rest/wifi';

    constructor(private http: HttpClient) { }

    /**
     * Scan for available WiFi networks.
     * @returns Observable of WiFi network array
     */
    scanWifiNetworks(): Observable<WifiNetwork[]> {
        return this.http.get<WifiNetwork[]>(`${this.wifiApiUrl}/scan`).pipe(
            catchError(err => {
                console.error('Error scanning WiFi networks:', err);
                return of([]);
            })
        );
    }

    /**
     * Connect to a WiFi network.
     * @param ssid Network SSID
     * @param password Network password (optional for open networks)
     * @returns Observable of connection response
     */
    connectToWifi(ssid: string, password?: string): Observable<WifiConnectionResponse> {
        const body: any = { ssid };
        if (password) {
            body.password = password;
        }

        return this.http.post<WifiConnectionResponse>(`${this.wifiApiUrl}/connect`, body).pipe(
            map(response => {
                console.log('WiFi connection response:', response);
                return response;
            }),
            catchError(err => {
                console.error('Error connecting to WiFi:', err);
                return of({
                    success: false,
                    error: err.error?.error || err.message || 'Connection failed'
                });
            })
        );
    }
}
