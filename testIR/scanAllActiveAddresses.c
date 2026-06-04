#include <stdio.h>
#include <stdlib.h>
#include <errno.h>
#include <string.h>
#include <unistd.h>
#include <stdint.h>
#include <modbus/modbus.h>

static int parse_slave_id(const char *text, const char *label, int *slave_id) {
    errno = 0;
    char *endptr = NULL;
    unsigned long parsed_value = strtoul(text, &endptr, 0);

    if (errno != 0 || endptr == text || *endptr != '\0' || parsed_value < 1 || parsed_value > 247) {
        fprintf(stderr, "Invalid %s '%s'. Use a Modbus slave ID from 1 to 247.\n",
                label, text);
        return 0;
    }

    *slave_id = (int) parsed_value;
    return 1;
}

int main(int argc, char **argv) {
    if (argc != 2 && argc != 4) {
        fprintf(stderr, "Usage: %s <serial_port> [start_id end_id]\n", argv[0]);
        fprintf(stderr, "Example: %s /dev/ttyS1\n", argv[0]);
        fprintf(stderr, "Example: %s /dev/ttyS1 1 110\n", argv[0]);
        return 1;
    }

    const char *port = argv[1];
    int min_slave_id = 1;
    int max_slave_id = 110;
    const int probe_register = 0x0000;
    int active_ids[247] = {0};
    int active_count = 0;

    if (argc == 4) {
        if (!parse_slave_id(argv[2], "start ID", &min_slave_id) ||
            !parse_slave_id(argv[3], "end ID", &max_slave_id)) {
            return 1;
        }

        if (min_slave_id > max_slave_id) {
            fprintf(stderr, "Invalid range: start ID %d is greater than end ID %d.\n",
                    min_slave_id, max_slave_id);
            return 1;
        }
    }

    modbus_t *ctx = modbus_new_rtu(port, 9600, 'N', 8, 1);
    if (!ctx) {
        fprintf(stderr, "Unable to create libmodbus context\n");
        return 1;
    }

    modbus_set_debug(ctx, 1);
    modbus_set_response_timeout(ctx, 1, 0);

    if (modbus_connect(ctx) == -1) {
        fprintf(stderr, "Connection failed: %s\n", modbus_strerror(errno));
        modbus_free(ctx);
        return 1;
    }

    printf("\n========================================\n");
    printf(">>> SCANNING ALL ACTIVE MODBUS ADDRESSES <<<\n");
    printf("========================================\n");
    printf("Port: %s\n", port);
    printf("Range: %d-%d\n", min_slave_id, max_slave_id);
    printf("Probe: read holding register 0x%04X\n\n", probe_register);

    for (int slave_id = min_slave_id; slave_id <= max_slave_id; slave_id++) {
        uint16_t read_value = 0;

        modbus_set_slave(ctx, slave_id);
        int rc = modbus_read_registers(ctx, probe_register, 1, &read_value);

        if (rc == 1) {
            active_ids[active_count] = slave_id;
            active_count++;

            printf("[ACTIVE] ID %d responded. Register 0x%04X = 0x%04X (%u)\n",
                   slave_id, probe_register, read_value, read_value);
        } else {
            printf("[----] ID %d no response: %s\n",
                   slave_id, modbus_strerror(errno));
        }

        usleep(100000);
    }

    printf("\n========================================\n");
    printf(">>> SCAN SUMMARY <<<\n");
    printf("========================================\n");
    printf("Active address count: %d\n", active_count);

    if (active_count > 0) {
        printf("Active addresses:");
        for (int i = 0; i < active_count; i++) {
            printf(" %d", active_ids[i]);
        }
        printf("\n");
    } else {
        printf("No active addresses found.\n");
    }

    modbus_close(ctx);
    modbus_free(ctx);
    return active_count > 0 ? 0 : 1;
}
