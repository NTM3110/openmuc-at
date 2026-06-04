#include <stdio.h>
#include <stdlib.h>
#include <errno.h>
#include <string.h>
#include <unistd.h>
#include <time.h>
#include <modbus/modbus.h>

int main(int argc, char **argv) {
    if (argc < 4) {
        fprintf(stderr, "Usage: %s <serial_port> <start_id> <end_id>\n", argv[0]);
        fprintf(stderr, "Example: %s /dev/ttyS1 1 110\n", argv[0]);
        return 1;
    }

    const char *port = argv[1];
    int start_id = atoi(argv[2]);
    int end_id = atoi(argv[3]);

    // Initialize Modbus RTU: 9600 N 8 1
    modbus_t *ctx = modbus_new_rtu(port, 9600, 'N', 8, 1);
    if (!ctx) {
        fprintf(stderr, "Unable to create libmodbus context\n");
        return 1;
    }

    // Enable debug to see the hex frames in the terminal
    modbus_set_debug(ctx, 1);

    // Set response timeout to 1 second
    modbus_set_response_timeout(ctx, 1, 0);

    if (modbus_connect(ctx) == -1) {
        fprintf(stderr, "Connection failed: %s\n", modbus_strerror(errno));
        modbus_free(ctx);
        return 1;
    }

    for (int current_id = start_id; current_id <= end_id; current_id++) {
        printf("\n========================================\n");
        printf(">>> SCANNING ID: %d <<<\n", current_id);
        printf("========================================\n");
        
        modbus_set_slave(ctx, current_id);

        // 1. Write 0xF0F0 to Register Address 12 (0x000C)
        if (modbus_write_register(ctx, 0x000C, 0xF0F0) == -1) {
            fprintf(stderr, "[ID %d] Write failed: %s\n", current_id, modbus_strerror(errno));
        } else {
            printf("[ID %d] Write 0xF0F0 successful.\n", current_id);
        }

        // 2. Polling Loop: 30-second window
        time_t start_poll = time(NULL);
        int success = 0;

        while (difftime(time(NULL), start_poll) < 30) {
            uint16_t regs[4] = {0};
            
            // Read 4 registers starting at address 1 (Reg 2)
            int rc = modbus_read_registers(ctx, 1, 4, regs);

            if (rc == 4) {
                uint16_t reg4_val = regs[2]; // Index 2 is Register 4
                printf("[ID %d] Polling... Reg 4: %u\n", current_id, reg4_val);

                if (reg4_val > 0) {
                    printf("[ID %d] CONDITION MET (> 0). Moving to rest phase...\n", current_id);
                    success = 1;
                    break;
                }
            }
            // Wait exactly 1 second between polls
            sleep(1); 
        }

        if (!success) {
            printf("[ID %d] 30s Timeout reached.\n", current_id);
        }

        // 3. Rest 20 seconds before the next ID
        if (current_id < end_id) {
            printf("\nID %d session finished. Resting 20 seconds...\n", current_id);
            sleep(20);
        }
    }

    modbus_close(ctx);
    modbus_free(ctx);
    printf("\nScan complete for range %d-%d.\n", start_id, end_id);
    return 0;
}
