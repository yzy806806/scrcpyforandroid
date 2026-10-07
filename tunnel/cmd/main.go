package main

import (
	"flag"
	"fmt"
	"log"
	"os"
	"strings"

	"quic-tunnel"
)

func main() {
	mode := flag.String("mode", "server", "server or client")
	listen := flag.String("listen", ":22289", "listen address")
	target := flag.String("target", "127.0.0.1:5555", "target address (server mode)")
	server := flag.String("server", "", "QUIC server address (client mode)")
	localPort := flag.Int("port", 0, "local TCP port (client mode)")
	flag.Parse()

	var psk string
	if key, err := os.ReadFile("/data/local/tmp/tunnel-key"); err == nil {
		psk = strings.TrimSpace(string(key))
	} else {
		psk = os.Getenv("TUNNEL_KEY")
	}
	if psk == "" {
		log.Fatal("No key found. Set /data/local/tmp/tunnel-key or TUNNEL_KEY env")
	}

	switch *mode {
	case "server":
		result := quictunnel.StartServer(*listen, *target, psk)
		if result != "OK" {
			log.Fatalf("Server start failed: %s", result)
		}
		log.Printf("Server running. Press Ctrl+C to stop.")
		select {} // block forever

	case "client":
		if *server == "" {
			log.Fatal("Client mode requires -server flag")
		}
		result := quictunnel.StartClient(*server, *localPort, psk)
		if result[:5] == "ERROR" {
			log.Fatalf("Client start failed: %s", result)
		}
		fmt.Printf("Client listening on 127.0.0.1:%s\n", result)
		select {} // block forever

	default:
		log.Fatalf("Unknown mode: %s", *mode)
	}
}
