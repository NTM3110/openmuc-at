// src/app/pages/settings/maintenance/maintenance.component.ts
import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTableModule } from '@angular/material/table';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { FormsModule } from '@angular/forms';

import { WifiService, WifiNetwork } from '../../../services/wifi.service';
import { Component as NgComponent, Inject } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';

@Component({
  selector: 'app-settings-maintenance',
  standalone: true,
  imports: [
    CommonModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatTableModule,
    MatProgressSpinnerModule,
    MatSnackBarModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    FormsModule
  ],
  templateUrl: './maintenance.component.html',
  styleUrls: ['./maintenance.component.scss']
})
export class SettingsMaintenanceComponent {
  // WiFi properties
  wifiNetworks: WifiNetwork[] = [];
  isScanning = false;
  displayedColumns: string[] = ['ssid', 'signal', 'security', 'actions'];

  constructor(
    private wifiService: WifiService,
    private snackBar: MatSnackBar,
    private dialog: MatDialog
  ) { }

  // UI only; handlers will be wired later
  uploadConfig(address: string) {
    // Placeholder for future implementation
  }

  /**
   * Scan for available WiFi networks
   */
  scanWifiNetworks() {
    this.isScanning = true;
    this.wifiNetworks = [];

    this.wifiService.scanWifiNetworks().subscribe({
      next: (networks) => {
        this.wifiNetworks = networks;
        this.isScanning = false;
        this.snackBar.open(`Found ${networks.length} WiFi networks`, 'Close', {
          duration: 3000
        });
      },
      error: (err) => {
        console.error('Error scanning WiFi:', err);
        this.isScanning = false;
        this.snackBar.open('Failed to scan WiFi networks', 'Close', {
          duration: 3000
        });
      }
    });
  }

  /**
   * Connect to a WiFi network
   */
  connectToNetwork(network: WifiNetwork) {
    // Check if network requires password
    const requiresPassword = network.security && network.security.trim() !== '';

    if (requiresPassword) {
      // Open password dialog
      const dialogRef = this.dialog.open(WifiPasswordDialog, {
        width: '400px',
        data: { ssid: network.ssid }
      });

      dialogRef.afterClosed().subscribe(password => {
        if (password) {
          this.performConnection(network.ssid, password);
        }
      });
    } else {
      // Connect without password
      this.performConnection(network.ssid);
    }
  }

  /**
   * Perform the actual WiFi connection
   */
  private performConnection(ssid: string, password?: string) {
    this.snackBar.open(`Connecting to ${ssid}...`, 'Close', {
      duration: 2000
    });

    this.wifiService.connectToWifi(ssid, password).subscribe({
      next: (response) => {
        if (response.success) {
          this.snackBar.open(`Successfully connected to ${ssid}`, 'Close', {
            duration: 5000
          });
          // Refresh the network list to show updated connection status
          this.scanWifiNetworks();
        } else {
          this.snackBar.open(`Failed to connect: ${response.error || 'Unknown error'}`, 'Close', {
            duration: 5000
          });
        }
      },
      error: (err) => {
        console.error('Error connecting to WiFi:', err);
        this.snackBar.open('Failed to connect to WiFi network', 'Close', {
          duration: 5000
        });
      }
    });
  }

  /**
   * Get signal strength icon based on signal level
   */
  getSignalIcon(signal: number): string {
    if (signal >= 75) return 'signal_wifi_4_bar';
    if (signal >= 50) return 'signal_wifi_3_bar';
    if (signal >= 25) return 'signal_wifi_2_bar';
    return 'signal_wifi_1_bar';
  }

  /**
   * Get signal strength color
   */
  getSignalColor(signal: number): string {
    if (signal >= 75) return 'primary';
    if (signal >= 50) return 'accent';
    return 'warn';
  }
}

/**
 * Dialog component for entering WiFi password
 */
@NgComponent({
  selector: 'wifi-password-dialog',
  standalone: true,
  imports: [
    CommonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    FormsModule
  ],
  template: `
    <h2 mat-dialog-title>Connect to {{ data.ssid }}</h2>
    <mat-dialog-content>
      <mat-form-field appearance="outline" style="width: 100%;">
        <mat-label>Password</mat-label>
        <input matInput type="password" [(ngModel)]="password" (keyup.enter)="onConnect()">
      </mat-form-field>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button (click)="onCancel()">Cancel</button>
      <button mat-flat-button color="primary" (click)="onConnect()" [disabled]="!password">Connect</button>
    </mat-dialog-actions>
  `
})
export class WifiPasswordDialog {
  password = '';

  constructor(
    public dialogRef: MatDialogRef<WifiPasswordDialog>,
    @Inject(MAT_DIALOG_DATA) public data: { ssid: string }
  ) { }

  onCancel(): void {
    this.dialogRef.close();
  }

  onConnect(): void {
    this.dialogRef.close(this.password);
  }
}
