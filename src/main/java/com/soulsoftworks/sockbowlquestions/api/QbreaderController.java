package com.soulsoftworks.sockbowlquestions.api;

import com.soulsoftworks.sockbowlquestions.client.QbreaderClient;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.service.QbreaderImportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST facade for browsing and importing qbreader.org packets. Returns only the
 * new packet's id + name; callers fetch the full packet via the existing GraphQL
 * {@code getPacketById} and then use it as a match packet.
 */
@RestController
@RequestMapping("/api/qbreader")
public class QbreaderController {

    private final QbreaderClient qbreader;
    private final QbreaderImportService importService;

    public QbreaderController(QbreaderClient qbreader, QbreaderImportService importService) {
        this.qbreader = qbreader;
        this.importService = importService;
    }

    /** All qbreader set names. */
    @GetMapping("/sets")
    public List<String> sets() {
        return qbreader.setList();
    }

    /** Number of packets in a set (for bounding the packet-number picker). */
    @GetMapping("/packet-count")
    public Map<String, Integer> packetCount(@RequestParam String setName) {
        return Map.of("numPackets", qbreader.numPackets(setName));
    }

    /** Import one published packet from a set. */
    @PostMapping("/import")
    public ImportResult importPacket(@RequestBody ImportRequest request) {
        Packet packet = importService.importPacket(request.setName(), request.packetNumber());
        return new ImportResult(packet.getId(), packet.getName());
    }

    /** Assemble and import a random packet matching the given filters. */
    @PostMapping("/import-random")
    public ImportResult importRandom(@RequestBody RandomRequest request) {
        Packet packet = importService.importRandomPacket(
                request.categories(),
                request.difficulties(),
                request.tossupCount() == null ? 20 : request.tossupCount(),
                request.bonusCount() == null ? 20 : request.bonusCount(),
                request.name());
        return new ImportResult(packet.getId(), packet.getName());
    }

    public record ImportRequest(String setName, Integer packetNumber) {}

    public record RandomRequest(List<String> categories, List<Integer> difficulties,
                                Integer tossupCount, Integer bonusCount, String name) {}

    public record ImportResult(String id, String name) {}
}
