#include <stdio.h>
#include <stdlib.h>
#include <errno.h>
#include <string.h>
#include <unistd.h>
#include <time.h>
#include <stdint.h>
#include <modbus/modbus.h>

static int parse_slave_id(const char *text, const char *label, uint16_t *slave_id) {
    errno = 0;
    char *endptr = NULL;
    unsigned long parsed_value = strtoul(text, &endptr, 0);

    if (errno != 0 || endptr == text || *endptr != '\0' || parsed_value < 1 || parsed_value > 247) {
        fprintf(stderr, "Invalid %s '%s'. Use a Modbus slave ID from 1 to 247.\n",
                label, text);
        return 0;
    }

    *slave_id = (uint16_t) parsed_value;
    return 1;
}

static int check_slave_response(modbus_t *ctx, int slave_id, int timeout_seconds) {
    const int first_register = 0x0000;
    time_t start_poll = time(NULL);

    modbus_set_slave(ctx, slave_id);

    while (difftime(time(NULL), start_poll) < timeout_seconds) {
        uint16_t read_value = 0;
        int rc = modbus_read_registers(ctx, first_register, 1, &read_value);

        if (rc == 1) {
            printf("[ID %d] Verified response from register 0x%04X: 0x%04X (%u)\n",
                   slave_id, first_register, read_value, read_value);
            return 1;
        }

        printf("[ID %d] Verification read failed: %s\n",
               slave_id, modbus_strerror(errno));
        sleep(1);
    }

    return 0;
}

static int write_register_with_retries(modbus_t *ctx, int slave_id, int register_address, uint16_t value, int attempts) {
    modbus_set_slave(ctx, slave_id);

    for (int attempt = 1; attempt <= attempts; attempt++) {
        printf("[ID %d] Write attempt %d/%d: register 0x%04X = 0x%04X (%u)\n",
               slave_id, attempt, attempts, register_address, value, value);
        modbus_flush(ctx);

        if (modbus_write_register(ctx, register_address, value) != -1) {
            printf("[ID %d] Write attempt %d returned normal confirmation.\n",
                   slave_id, attempt);
            return 1;
        }

        fprintf(stderr, "[ID %d] Write attempt %d failed: %s\n",
                slave_id, attempt, modbus_strerror(errno));

        if (attempt < attempts) {
            sleep(1);
        }
    }

    return 0;
}

int main(int argc, char **argv) {
    if (argc < 4) {
        fprintf(stderr, "Usage: %s <serial_port> <current_slave_id> <new_slave_id>\n", argv[0]);
        fprintf(stderr, "Example: %s /dev/ttyS1 5 2\n", argv[0]);
        return 1;
    }

    const char *port = argv[1];
    const int slave_id_register = 0x000E;
    uint16_t current_slave_id = 0;
    uint16_t new_slave_id = 0;

    if (!parse_slave_id(argv[2], "current slave ID", &current_slave_id) ||
        !parse_slave_id(argv[3], "new slave ID", &new_slave_id)) {
        return 1;
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
    printf(">>> KNOWN ADDRESS CHANGE PHASE <<<\n");
    printf("========================================\n");
    printf("Changing module address from ID %u to ID %u.\n",
           current_slave_id, new_slave_id);

    printf("Verifying current ID %u responds before writing...\n", current_slave_id);
    if (!check_slave_response(ctx, current_slave_id, 5)) {
        fprintf(stderr, "Current ID %u did not respond. Address change aborted.\n",
                current_slave_id);
        modbus_close(ctx);
        modbus_free(ctx);
        return 1;
    }

    printf("Writing new address with Function 06: register 0x%04X = %u.\n",
           slave_id_register, new_slave_id);
    if (!write_register_with_retries(ctx, current_slave_id, slave_id_register, new_slave_id, 3)) {
        printf("Address register write did not return normal confirmation after retries.\n");
        printf("Continuing to verify new ID because the module may still have processed one write.\n");
    }

    printf("\n========================================\n");
    printf(">>> VERIFYING NEW ADDRESS <<<\n");
    printf("========================================\n");
    printf("Checking whether module moved from ID %u to ID %u...\n",
           current_slave_id, new_slave_id);

    sleep(1);
    modbus_flush(ctx);

    if (!check_slave_response(ctx, new_slave_id, 10)) {
        fprintf(stderr, "Address change failed: new ID %u did not respond.\n",
                new_slave_id);

        printf("Checking old ID %u for diagnostics...\n", current_slave_id);
        if (check_slave_response(ctx, current_slave_id, 3)) {
            fprintf(stderr, "Module is still responding at old ID %u.\n",
                    current_slave_id);
        }

        modbus_close(ctx);
        modbus_free(ctx);
        return 1;
    }

    printf("\n========================================\n");
    printf(">>> ADDRESS CHANGE SUCCESSFUL <<<\n");
    printf("========================================\n");
    printf("Module changed from ID %u to ID %u.\n",
           current_slave_id, new_slave_id);

    modbus_close(ctx);
    modbus_free(ctx);
    return 0;
}
