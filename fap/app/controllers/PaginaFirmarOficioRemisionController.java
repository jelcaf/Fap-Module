package controllers;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import messages.Messages;
import messages.Messages.MessageType;
import models.Agente;
import models.Documento;
import models.ExpedienteAed;
import models.Firmante;
import models.Firmantes;
import models.LineaResolucionFAP;
import models.ResolucionFAP;
import models.SolicitudGenerica;
import platino.FirmaUtils;
import play.mvc.After;
import play.mvc.Before;
import play.mvc.Util;
import properties.FapProperties;
import resolucion.ResolucionBase;
import security.Accion;
import services.GestorDocumentalService;
import services.RegistroService;
import config.InjectorConfig;
import controllers.fap.AgenteController;
import controllers.fap.ResolucionControllerFAP;
import controllers.gen.PaginaFirmarOficioRemisionControllerGen;
import enumerado.fap.gen.RolesEnum;

public class PaginaFirmarOficioRemisionController extends PaginaFirmarOficioRemisionControllerGen {

	public static void index(String accion, Long idResolucionFAP, Long idLineaResolucionFAP) {
		if (accion == null)
			accion = getAccion();
		if (!permiso(accion)) {
			Messages.fatal("No tiene permisos suficientes para realizar esta acción");
			renderTemplate("gen/PaginaFirmarOficioRemision/PaginaFirmarOficioRemision.html");
		}

		ResolucionFAP resolucionFAP = PaginaFirmarOficioRemisionController.getResolucionFAP(idResolucionFAP);

		LineaResolucionFAP lineaResolucionFAP = null;
		if ("crear".equals(accion)) {
			lineaResolucionFAP = PaginaFirmarOficioRemisionController.getLineaResolucionFAP();
			if (properties.FapProperties.getBoolean("fap.entidades.guardar.antes")) {

				lineaResolucionFAP.save();
				idLineaResolucionFAP = lineaResolucionFAP.id;
				resolucionFAP.lineasResolucion.add(lineaResolucionFAP);
				resolucionFAP.save();

				accion = "editar";
			}

		} else if (!"borrado".equals(accion))
			lineaResolucionFAP = PaginaFirmarOficioRemisionController.getLineaResolucionFAP(idResolucionFAP, idLineaResolucionFAP);

		log.info("Visitando página: " + "gen/PaginaFirmarOficioRemision/PaginaFirmarOficioRemision.html" + ", usuario: " + AgenteController.getAgente().name + " Solicitud: " + params.get("idSolicitud"));
		renderTemplate("fap/PaginaFirmarOficioRemision/PaginaFirmarOficioRemision.html", accion, idResolucionFAP, idLineaResolucionFAP, resolucionFAP, lineaResolucionFAP);
	}
	
	@Util
	public static void firmaFirmarOficioRemisionFormFirmarOficioRemision(Long idResolucionFAP, Long idLineaResolucionFAP, String firma) {
		LineaResolucionFAP lineaResolucionFAP = PaginaFirmarOficioRemisionController.getLineaResolucionFAP(idResolucionFAP, idLineaResolucionFAP);

		play.Logger.info("Metodo: firmaFirmarOficioRemisionFormFirmarOficioRemision");
		Map<String, Long> ids = (Map<String, Long>) tags.TagMapStack.top("idParams");
		Map<String, Object> vars = new HashMap<String, Object>();
		if (secure.checkAcceso("editarFirma", "editar", ids, vars)) {
			//if (lineaResolucionFAP.registro.firmantes.todos == null || lineaResolucionFAP.registro.firmantes.todos.size() == 0) {
			
			//Calculo que todos los firmantes sean Gestores.
			List<Agente> agentes = Agente.find("select agente from Agente agente join agente.roles rol where rol = ?", RolesEnum.gestor.name()).fetch();
			play.Logger.info("Lista de Gestores que pueden firmar el Oficio de Remision Generado");
			lineaResolucionFAP.registro.firmantes.todos = new ArrayList<Firmante>();
			for (int i = 0; i < agentes.size(); i++) {
				Firmante firmante = new Firmante(agentes.get(i));
				lineaResolucionFAP.registro.firmantes.todos.add(firmante);
				play.Logger.info("Agente con gestor: "+firmante.nombre);
			}
			lineaResolucionFAP.registro.firmantes.save();
			//}
			if (!lineaResolucionFAP.registro.fasesRegistro.firmada) {
				FirmaUtils.firmar(lineaResolucionFAP.registro.oficial, lineaResolucionFAP.registro.firmantes.todos, firma, null);
				if (!Messages.hasErrors()) {
					lineaResolucionFAP.registro.fasesRegistro.firmada = true;
				} else {
					play.Logger.error("Se produjeron errores en la firma, inténtelo de nuevo");
				}
			}
		} else {
			//ERROR
			Messages.error("No tiene permisos suficientes para realizar la acción");
		}
		if (!Messages.hasErrors()) {
			lineaResolucionFAP.save();

			SolicitudGenerica solicitud = SolicitudGenerica.findById(lineaResolucionFAP.solicitud.id);
			RegistroService registroService = InjectorConfig.getInjector().getInstance(RegistroService.class);
			GestorDocumentalService gestorDocumentalService = InjectorConfig.getInjector().getInstance(GestorDocumentalService.class);
			
			try  {
				
				if (!lineaResolucionFAP.registro.fasesRegistro.registro){
					play.Logger.info("Se inicia el proceso de Registro");
					// Se obtiene el justificante de registro de salida del oficio de remisión
					models.JustificanteRegistro justificanteSalida = registroService.registroDeSalida(solicitud.solicitante, lineaResolucionFAP.registro.oficial, solicitud.expedientePlatino, "Oficio de remisión");				
					lineaResolucionFAP.registro.informacionRegistro.setDataFromJustificante(justificanteSalida);
					Documento documento = lineaResolucionFAP.registro.justificante;
					documento.tipo = FapProperties.get("fap.aed.tiposdocumentos.justificanteRegistroSalida");
					documento.descripcion = "Justificante de registro de salida del oficio de remisión";
					documento.save();
					play.Logger.info("Creado el documento de justificante en local, se procede a almacenar en el AED");
					InputStream is = justificanteSalida.getDocumento().contenido.getInputStream();
					gestorDocumentalService.saveDocumentoTemporal(documento, is, "JustificanteOficioRemision" + ".pdf");
					play.Logger.info("Justificante del documento oficio de remisión almacenado en el AED");
					lineaResolucionFAP.registro.fasesRegistro.registro = true;

					List<Documento> documentos = new ArrayList<Documento>();
					documentos.add(lineaResolucionFAP.registro.oficial);
					documentos.add(lineaResolucionFAP.registro.justificante);
					
					// Se pone la fecha de registro a los documentos
					for (Documento doc: documentos) {
						if (doc.fechaRegistro == null) {
							doc.fechaRegistro = lineaResolucionFAP.registro.informacionRegistro.fechaRegistro;
							doc.save();
						}
					}
					Messages.ok("Se realizó el registro correctamente");
				}
				if (!lineaResolucionFAP.registro.fasesRegistro.clasificarAed){
					List<Documento> documentos = new ArrayList<Documento>();
					documentos.add(lineaResolucionFAP.registro.oficial);
					documentos.add(lineaResolucionFAP.registro.justificante);
					play.Logger.info("Se procede a clasificar los documentos oficial y justificante de la línea: "+lineaResolucionFAP.id);
					// Se clasifican los documentos
					gestorDocumentalService.clasificarDocumentos(solicitud, documentos, false);
					lineaResolucionFAP.registro.fasesRegistro.clasificarAed = true;
					play.Logger.info("Documentos clasificados");
					lineaResolucionFAP.save();
					solicitud.save();
					Messages.ok("Se realizó la clasificación correctamente");
				}
				
			} catch (Throwable e)   {
				Messages.error("Error almacenando el justificante del documento de oficio de remisión en el AED");
				play.Logger.info("Error almacenando el justificante del documento de oficio de remisión en el AED");
			}
		}
		
		if (!Messages.hasErrors()) {

			log.info("Acción Editar de página: " + "gen/PaginaFirmarOficioRemision/PaginaFirmarOficioRemision.html" + " , intentada con éxito" + ", usuario: " + AgenteController.getAgente().name + " Solicitud: " + params.get("idSolicitud"));
		} else
			log.info("Acción Editar de página: " + "gen/PaginaFirmarOficioRemision/PaginaFirmarOficioRemision.html" + " , intentada sin éxito (Problemas de Validación)");

		redirect("PaginaNotificarResolucionController.index", "editar", idResolucionFAP);
	}
	
}