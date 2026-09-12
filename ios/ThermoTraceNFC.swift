import CoreNFC
import Foundation

// =====================================================================
// ThermoTrace — camada NFC iOS
//
// Porte enxuto do `NFCTagHelper.m` do fabricante (48 KB num único arquivo,
// Objective-C, com toda a lógica de UI e de protocolo misturada).
//
// O que este arquivo prova: **o ciclo completo roda em Core NFC PÚBLICO.**
// A evidência está no próprio material do fornecedor:
//   - `FMTemperature.entitlements` pede só `com.apple.developer.nfc.
//     readersession.formats = [TAG]`;
//   - `NFCTagObject.m` usa `customCommandWithRequestFlag:customCommandCode:
//     customRequestParameters:`, que é API pública desde o iOS 13.
// Nenhuma API privada, nenhum hardware proprietário.
//
// Requisitos do projeto Xcode:
//   1. Capability "Near Field Communication Tag Reading";
//   2. entitlement acima com o formato TAG;
//   3. `NFCReaderUsageDescription` no Info.plist;
//   4. iPhone 7+ / iOS 13+ (este arquivo usa async/await: iOS 15+).
// =====================================================================

// ---------------------------------------------------------------------
// Mapa de comandos
// ---------------------------------------------------------------------
//
// ATENÇÃO — existem DOIS mapas incompatíveis no material do fornecedor:
//
//   a) `nfcinstruct` (SDK Android) + `nfcTemperature.pch` (iOS)
//      → grava o relógio no endereço 0x0140
//   b) app DT160 9.3.5 (`com/fmsh/temperature/util/NFCUtils.java`)
//      → grava o relógio no endereço 0x0020 / 0x001A
//
// Os endereços do iOS batem com (a). Adotamos (a) como canônico, porque é o
// único mapa que existe nas duas plataformas. Antes de produção, confirme
// contra a etiqueta física: escrever no endereço errado corrompe a memória.

enum FMCommand {
    // Em ISO 15693 o primeiro byte é o CÓDIGO do comando customizado e o
    // resto são parâmetros. O Core NFC insere sozinho o manufacturer code
    // (0x1D, Fudan) — por isso, ao contrário do Android, ele não aparece aqui.
    static let wakeUp             = "C400"
    static let pdStatus           = "C480"
    static let sleep              = "C301"
    static let checkStatus        = "CF010000"
    static let uhfInit            = "CE00"          // "UHF" no nome, mas inicializa registradores via NFC
    static let ledOn              = "C902"
    static let ledOff             = "C901"
    static let field              = "D000"

    static let onceTemperature    = "C00600"        // dispara medição instantânea
    static let onceResult         = "C08400"        // lê o resultado

    static let voltageConfig      = "C5C0120008"
    static let voltageStart       = "C01200"
    static let voltageRead        = "C09200"
    static let voltageRestore     = "C5C0120000"

    static func setDelay(_ hex: String)        -> String { "C5C08400" + hex }   // tag
    static func setAppDelay(_ hex: String)     -> String { "B301100100" + hex } // app
    static func setInterval(_ hex: String)     -> String { "C5C085" + hex }
    static func setAppInterval(_ hex: String)  -> String { "B3011401" + hex }
    static func setCount(_ hex: String)        -> String { "B3B09401" + hex }
    static func setMinTemp(_ hex: String)      -> String { "B3B08001" + hex }
    static func setMaxTemp(_ hex: String)      -> String { "B3B08201" + hex }
    static func setStartTime(_ hex: String)    -> String { "B3014003" + hex }

    static let startLogging       = "C20000000000"
    static let getStartTime       = "B101400003"
    static let getSize            = "B1B0540003"
    static let getCount           = "B1B0940003"
    static let getMinMaxRange     = "B1B0800003"
    static let idleRecordedCount  = "B1B1880000"
    static let busyRecordedCount  = "C6C091"
    static func readMemory(addressHex: String, lengthHex: String) -> String {
        "B1" + addressHex + lengthHex
    }

    // Códigos de retorno
    static let outOfPowerDown = "5555"
    static let successZ       = "0000"
    static let stopSuccess    = "0100"
}

// ---------------------------------------------------------------------
// Erros
// ---------------------------------------------------------------------

enum ThermoTraceNFCError: LocalizedError {
    case notSupported
    case wrongTag(expected: String, found: String)
    case notISO15693
    case alreadyLogging
    case wakeUpFailed
    case commandFailed(command: String, underlying: Error?)
    case unexpectedResponse(command: String, response: String)
    case lowBattery(volts: Double, minimum: Double)
    case sessionTimedOut

    var errorDescription: String? {
        switch self {
        case .notSupported:
            return "Este iPhone não suporta leitura de etiquetas NFC."
        case .wrongTag(let expected, let found):
            return "Etiqueta errada. Esperada \(expected), encontrada \(found). "
                 + "Confira o QR do volume."
        case .notISO15693:
            return "Etiqueta detectada não é ISO 15693."
        case .alreadyLogging:
            return "Esta etiqueta já está monitorando. Encerre a sessão anterior."
        case .wakeUpFailed:
            return "A etiqueta não acordou. Reaproxime e mantenha parado."
        case .commandFailed(let cmd, let err):
            return "Falha no comando \(cmd): \(err?.localizedDescription ?? "sem detalhe")"
        case .unexpectedResponse(let cmd, let resp):
            return "Resposta inesperada em \(cmd): \(resp)"
        case .lowBattery(let v, let min):
            return String(format: "Bateria em %.2f V (mínimo %.2f V). Use outra etiqueta.", v, min)
        case .sessionTimedOut:
            return "A sessão NFC do iOS expirou. Aproxime novamente para continuar."
        }
    }
}

// ---------------------------------------------------------------------
// Transporte
// ---------------------------------------------------------------------

/// Envia comandos customizados ISO 15693 para uma etiqueta FM13DT160.
struct FMTagTransport {
    let tag: NFCISO15693Tag

    /// UID canônico.
    ///
    /// `NFCISO15693Tag.identifier` vem em ordem inversa à que o Android
    /// produz. O próprio SDK do fornecedor faz esta inversão em
    /// `NFCTagObject.m` para casar com o app Android — ou seja, a diferença
    /// é real e conhecida por eles. Se não normalizarmos, a mesma etiqueta
    /// gera duas chaves diferentes no banco.
    var canonicalUID: String {
        tag.identifier.reversed().map { String(format: "%02X", $0) }.joined()
    }

    /// `command` = 1 byte de código + N bytes de parâmetros, em hex.
    @discardableResult
    func send(_ command: String) async throws -> String {
        guard let bytes = Data(hexString: command), let code = bytes.first else {
            throw ThermoTraceNFCError.unexpectedResponse(command: command, response: "comando vazio")
        }
        let params = bytes.dropFirst()

        do {
            let response = try await tag.customCommand(
                requestFlags: [.highDataRate],
                customCommandCode: Int(code),
                customRequestParameters: Data(params)
            )
            return response.map { String(format: "%02X", $0) }.joined()
        } catch {
            throw ThermoTraceNFCError.commandFailed(command: command, underlying: error)
        }
    }
}

// ---------------------------------------------------------------------
// Operações
// ---------------------------------------------------------------------

struct TagSnapshot {
    let uid: String
    let temperatureC: Double?
    let voltageV: Double?
}

struct ActivationOutcome {
    let uid: String
    /// Epoch gravado NA etiqueta. Com `timeBase == .firstWindow`, já inclui o delay.
    let tagStartEpoch: Int
    let deviceStartAt: Date
    let timeBase: TimeBase
    let verified: Bool
    let voltageV: Double?
}

/// Espelha `TimeBase` do Android (`SessionDecoder.kt`).
enum TimeBase: String {
    case startInstant = "START_INSTANT"   // Android: grava `now`
    case firstWindow  = "FIRST_WINDOW"    // iOS: grava `now + delay*60`
}

struct ActivationPlan {
    var delayMinutes: Int = 0
    var intervalSeconds: Int = 600
    var loggingCount: Int
    var minC: Int
    var maxC: Int
}

enum FMOperations {

    static let minimumStartVoltage = 1.40

    // -- identificação -------------------------------------------------

    static func identify(_ t: FMTagTransport) async throws -> TagSnapshot {
        try await wakeUp(t)

        try await t.send(FMCommand.onceTemperature)
        let tempHex = try await t.send(FMCommand.onceResult)

        let voltage = try? await readVoltage(t)

        return TagSnapshot(
            uid: t.canonicalUID,
            temperatureC: Temperature.decode(tempHex),
            voltageV: voltage
        )
    }

    // -- ativação ------------------------------------------------------

    /// Sequência de START. Segue o fluxo validado do fornecedor, com três
    /// diferenças deliberadas em relação ao `NFCTagHelper.m`:
    ///
    ///  1. bloqueia a ativação se a bateria estiver abaixo do piso;
    ///  2. reconsulta o status depois do START e devolve `verified`;
    ///  3. devolve o epoch efetivamente gravado + a convenção usada, para
    ///     que o backend reconstrua os timestamps sem adivinhar.
    static func startLogging(_ t: FMTagTransport, plan: ActivationPlan) async throws -> ActivationOutcome {
        // 1. já está medindo?
        let status = try await t.send(FMCommand.checkStatus)
        if isLogging(status) { throw ThermoTraceNFCError.alreadyLogging }

        // 2. bateria
        let voltage = try? await readVoltage(t)
        if let v = voltage, v < minimumStartVoltage {
            throw ThermoTraceNFCError.lowBattery(volts: v, minimum: minimumStartVoltage)
        }

        // 3. acordar
        try await wakeUp(t)

        // 4. parâmetros — gravados em DOIS lugares: registrador (o chip usa
        //    para medir) e EEPROM (o app usa para reler). Os dois precisam ir.
        let delayHex    = String(format: "%02X", plan.delayMinutes)
        let intervalHex = String(format: "%04X", plan.intervalSeconds)
        let countHex    = le16(plan.loggingCount)

        try await t.send(FMCommand.setDelay(delayHex))
        try await t.send(FMCommand.setAppDelay(delayHex))
        try await t.send(FMCommand.setInterval(intervalHex))
        try await t.send(FMCommand.setAppInterval(intervalHex))
        try await t.send(FMCommand.setCount(countHex))
        try await t.send(FMCommand.setMinTemp(Temperature.encode(plan.minC)))
        try await t.send(FMCommand.setMaxTemp(Temperature.encode(plan.maxC)))

        // 5. relógio.
        //    A etiqueta NÃO tem relógio confiável: quem define a base de tempo
        //    de toda a auditoria é ESTE iPhone, agora. Guarde `deviceStartAt`
        //    e o desvio contra o servidor — sem isso o laudo não se defende.
        let deviceStartAt = Date()
        let startEpoch = Int(deviceStartAt.timeIntervalSince1970) + plan.delayMinutes * 60
        try await t.send(FMCommand.setStartTime(String(format: "%08X", startEpoch)))

        // 6. START
        let started = try await t.send(FMCommand.startLogging)
        guard started.contains(FMCommand.successZ) else {
            throw ThermoTraceNFCError.unexpectedResponse(command: FMCommand.startLogging, response: started)
        }

        // 7. confirmação
        let after = try await t.send(FMCommand.checkStatus)

        return ActivationOutcome(
            uid: t.canonicalUID,
            tagStartEpoch: startEpoch,
            deviceStartAt: deviceStartAt,
            timeBase: .firstWindow,
            verified: isLogging(after),
            voltageV: voltage
        )
    }

    // -- auxiliares ----------------------------------------------------

    private static func wakeUp(_ t: FMTagTransport) async throws {
        try await t.send(FMCommand.wakeUp)
        let pd = try await t.send(FMCommand.pdStatus)
        guard pd.contains(FMCommand.outOfPowerDown) else {
            throw ThermoTraceNFCError.wakeUpFailed
        }
    }

    /// bit12 do status = 1 significa "em fluxo RTC" (medindo).
    private static func isLogging(_ statusHex: String) -> Bool {
        guard let data = Data(hexString: statusHex), data.count >= 2 else { return false }
        return (data[data.count - 2] & 0x10) != 0
    }

    private static func readVoltage(_ t: FMTagTransport) async throws -> Double {
        try await t.send(FMCommand.voltageConfig)
        try await t.send(FMCommand.voltageStart)
        let raw = try await t.send(FMCommand.voltageRead)
        try await t.send(FMCommand.voltageRestore)
        return Voltage.decode(raw)
    }

    /// 2 bytes little-endian, como o chip espera nos campos de contagem.
    private static func le16(_ value: Int) -> String {
        String(format: "%02X%02X", value & 0xFF, (value >> 8) & 0xFF)
    }
}

// ---------------------------------------------------------------------
// Conversões de temperatura
// ---------------------------------------------------------------------

enum Temperature {
    /// Os registradores de limite guardam `°C * 4` (LSB de 0,25 °C).
    /// Negativos entram em complemento de 2 sobre 10 bits (+0x400).
    static func encode(_ celsius: Int) -> String {
        let raw = celsius < 0 ? (celsius * 4 + 0x400) : (celsius * 4)
        return String(format: "%02X%02X", raw & 0xFF, (raw >> 8) & 0xFF)
    }

    /// Amostra de 10 bits com sinal. `divisor` = 4 (0,25 °C) ou 8 (0,125 °C),
    /// conforme o bit de configuração do chip.
    static func decode(_ hex: String, divisor: Double = 4.0) -> Double? {
        guard hex.count >= 4 else { return nil }
        let tail = String(hex.suffix(4))
        // little-endian: troca os dois bytes
        let swapped = String(tail.suffix(2)) + String(tail.prefix(2))
        guard let value = Int(swapped, radix: 16) else { return nil }
        let tenBits = value & 0x3FF
        let signed = (tenBits & 0x200) != 0 ? tenBits - 0x400 : tenBits
        return Double(signed) / divisor
    }
}

enum Voltage {
    /// Placeholder: a conversão exata do ADC de bateria precisa ser confirmada
    /// contra o datasheet do IC (item ainda em aberto com o fornecedor).
    /// Até lá, trate o valor como indicativo e não o imprima no laudo.
    static func decode(_ hex: String) -> Double {
        guard hex.count >= 4, let raw = Int(String(hex.suffix(4)), radix: 16) else { return 0 }
        return Double(raw & 0x3FF) * 1.5 / 1023.0
    }
}

// ---------------------------------------------------------------------
// Sessão
// ---------------------------------------------------------------------

/// Conduz uma sessão `NFCTagReaderSession`.
///
/// Duas restrições do iOS que mudam o desenho em relação ao Android:
///
///  - a sessão expira em torno de 20 s e não roda em background. Baixar 4.864
///    pontos não cabe numa sessão só: leia em blocos, guarde o progresso e
///    peça ao usuário para reaproximar. `alertMessage` é o canal para isso.
///  - só polling ISO15693 é usado aqui de propósito. Comandos proprietários
///    `0xC0–0xCF` NÃO passam pelo caminho MiFare/14443 do Core NFC.
@available(iOS 15.0, *)
final class ThermoTraceSession: NSObject, NFCTagReaderSessionDelegate {

    /// UID que deve estar na etiqueta. Vem do QR do volume.
    /// Nada é escrito antes desta conferência.
    private let expectedUID: String?
    private let work: (FMTagTransport) async throws -> String
    private let completion: (Result<String, Error>) -> Void
    private var session: NFCTagReaderSession?

    init(expectedUID: String?,
         alert: String = "Aproxime o iPhone da etiqueta",
         work: @escaping (FMTagTransport) async throws -> String,
         completion: @escaping (Result<String, Error>) -> Void) {
        self.expectedUID = expectedUID?.uppercased()
        self.work = work
        self.completion = completion
        super.init()

        guard NFCTagReaderSession.readingAvailable else {
            completion(.failure(ThermoTraceNFCError.notSupported))
            return
        }
        session = NFCTagReaderSession(pollingOption: .iso15693, delegate: self, queue: nil)
        session?.alertMessage = alert
        session?.begin()
    }

    func tagReaderSessionDidBecomeActive(_ session: NFCTagReaderSession) {}

    func tagReaderSession(_ session: NFCTagReaderSession, didDetect tags: [NFCTag]) {
        guard case let .iso15693(tag) = tags.first else {
            session.invalidate(errorMessage: ThermoTraceNFCError.notISO15693.localizedDescription)
            return
        }

        Task {
            do {
                try await session.connect(to: tags[0])
                let transport = FMTagTransport(tag: tag)

                // Barreira de identidade — o equivalente do defeito D1 no Android.
                if let expected = expectedUID, expected != transport.canonicalUID {
                    let err = ThermoTraceNFCError.wrongTag(expected: expected, found: transport.canonicalUID)
                    session.invalidate(errorMessage: err.localizedDescription)
                    completion(.failure(err))
                    return
                }

                let result = try await work(transport)
                session.alertMessage = "Concluído"
                session.invalidate()
                completion(.success(result))
            } catch {
                session.invalidate(errorMessage: error.localizedDescription)
                completion(.failure(error))
            }
        }
    }

    func tagReaderSession(_ session: NFCTagReaderSession, didInvalidateWithError error: Error) {
        self.session = nil
    }
}

// ---------------------------------------------------------------------

extension Data {
    init?(hexString: String) {
        let chars = Array(hexString.uppercased())
        guard chars.count % 2 == 0 else { return nil }
        var bytes = [UInt8]()
        bytes.reserveCapacity(chars.count / 2)
        for i in stride(from: 0, to: chars.count, by: 2) {
            guard let b = UInt8(String(chars[i...i+1]), radix: 16) else { return nil }
            bytes.append(b)
        }
        self.init(bytes)
    }
}
